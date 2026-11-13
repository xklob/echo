package com.klob.echo.speech;

import android.app.*;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.work.*;
import com.klob.echo.R;
import com.klob.echo.core.WaveformPeaks;
import com.klob.echo.settings.AppSettings;
import com.klob.echo.storage.ClipStore;
import java.io.*;
import java.util.*;
import org.json.*;
import org.vosk.*;

/** Durable, cancelable jobs. One speech job runs at a time; waveform work can run alongside it. */
public final class AnalysisWorker extends Worker {
  private static final java.util.concurrent.locks.ReentrantLock SPEECH_LOCK =
      new java.util.concurrent.locks.ReentrantLock();

  public AnalysisWorker(Context context, WorkerParameters parameters) {
    super(context, parameters);
  }

  public static String tag(File file) {
    return "clip-analysis:" + file.getAbsolutePath();
  }

  public static void enqueue(Context context, File audio, String mode) {
    OneTimeWorkRequest work =
        new OneTimeWorkRequest.Builder(AnalysisWorker.class)
            .setInputData(
                new Data.Builder()
                    .putString("file", audio.getAbsolutePath())
                    .putString("mode", mode)
                    .build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
            .addTag(tag(audio))
            .addTag("analysis-created:" + System.currentTimeMillis())
            .build();
    WorkManager manager = WorkManager.getInstance(context);
    manager.enqueueUniqueWork(
        tag(audio) + (mode.equals("wave") ? ":wave" : ":speech"),
        mode.equals("wave") ? ExistingWorkPolicy.KEEP : ExistingWorkPolicy.APPEND_OR_REPLACE,
        work);
  }

  public static void saved(Context context, File audio) {
    android.content.SharedPreferences prefs = AppSettings.prefs(context);
    if (prefs.getBoolean("auto_transcribe", false)) enqueue(context, audio, "transcript");
    else if (prefs.getBoolean("auto_analyze_speech", false)) enqueue(context, audio, "speech");
  }

  public static void cancel(Context context, File audio) {
    WorkManager.getInstance(context).cancelAllWorkByTag(tag(audio));
  }

  @Override
  public Result doWork() {
    File audio =
        new File(getInputData().getString("file") == null ? "" : getInputData().getString("file"));
    String mode = getInputData().getString("mode");
    if (mode == null || !audio.isFile()) return failure("Recording is no longer available.");
    boolean speechLock = !mode.equals("wave");
    if (speechLock && !SPEECH_LOCK.tryLock()) return Result.retry();
    try {
      if (!audio
              .getCanonicalPath()
              .startsWith(
                  new File(getApplicationContext().getFilesDir(), "clips").getCanonicalPath()
                      + File.separator)
          || !ClipStore.isAudio(audio.getName())) return failure("Invalid recording.");
      if (mode.equals("wave") && ClipAnalysis.readWaveform(audio) != null) return Result.success();
      setForegroundAsync(foreground(mode)).get();
      process(audio, mode);
      return Result.success();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return failure("Processing cancelled. Try again when ready.");
    } catch (Exception | OutOfMemoryError e) {
      String reason =
          mode.equals("transcript") && !SpeechModels.installed(getApplicationContext())
              ? "Download the English speech model in Settings, then try again."
              : "Could not process this recording. Check free storage and try again.";
      android.util.Log.w("ClipAnalysis", "Processing failed: " + e.getClass().getSimpleName(), e);
      return failure(reason);
    } finally {
      if (speechLock) SPEECH_LOCK.unlock();
    }
  }

  private Result failure(String text) {
    return Result.failure(new Data.Builder().putString("error", text).build());
  }

  private ForegroundInfo foreground(String mode) {
    Context context = getApplicationContext();
    context
        .getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                "analysis", "Recording analysis", NotificationManager.IMPORTANCE_LOW));
    Notification notification =
        new NotificationCompat.Builder(context, "analysis")
            .setSmallIcon(R.drawable.ic_stat_notify_recording)
            .setContentTitle(
                mode.equals("transcript")
                    ? "Transcribing recording"
                    : mode.equals("speech") ? "Finding speech" : "Preparing waveform")
            .setContentText("Processing on this device")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(
                0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(getId()))
            .build();
    return new ForegroundInfo(
        6000 + Math.floorMod(getId().hashCode(), 100000),
        notification,
        Build.VERSION.SDK_INT >= 35
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
  }

  private void process(File audio, String mode) throws Exception {
    long bytes = audio.length(), modified = audio.lastModified();
    ClipAnalysis result = ClipAnalysis.read(audio);
    boolean transcribe = mode.equals("transcript");
    boolean detect = mode.equals("speech") || (transcribe && !result.analyzed);
    if (transcribe && !SpeechModels.installed(getApplicationContext()))
      throw new IOException("No model");
    WaveformPeaks peaks = new WaveformPeaks();
    SpeechDetector detector = null;
    Recognizer recognizer = null;
    boolean acquired = false;
    try {
      if (detect) detector = new SpeechDetector(getApplicationContext());
      if (transcribe) {
        Model model = SpeechModels.acquire(getApplicationContext());
        acquired = true;
        recognizer = new Recognizer(model, 16000);
        recognizer.setWords(true);
        result.words.clear();
      }
      final SpeechDetector vad = detector;
      final Recognizer speech = recognizer;
      final byte[] buffer = new byte[8192];
      final long[] progressAt = {0};
      AudioDecoder.decode(
          audio,
          (pcm, count) -> {
            if (isStopped()) throw new InterruptedException();
            peaks.accept(pcm, count);
            if (vad != null) vad.accept(pcm, count);
            if (speech != null) {
              for (int i = 0; i < count; i++) {
                buffer[i * 2] = (byte) pcm[i];
                buffer[i * 2 + 1] = (byte) (pcm[i] >> 8);
              }
              if (speech.acceptWaveForm(buffer, count * 2)) appendWords(result, speech.getResult());
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - progressAt[0] >= 500) {
              setProgressAsync(
                  new Data.Builder()
                      .putLong("processed_ms", peaks.samples() * 1000 / 16000)
                      .build());
              progressAt[0] = now;
            }
          });
      result.duration = peaks.samples() * 1000 / 16000;
      if (vad != null) {
        result.speech.clear();
        result.speech.addAll(vad.finish());
        result.analyzed = true;
      }
      if (speech != null) {
        appendWords(result, speech.getFinalResult());
        result.transcribed = true;
      }
      short[][] values = peaks.finish();
      ClipAnalysis.Waveform waveform =
          new ClipAnalysis.Waveform(peaks.samplesPerBin(), peaks.samples(), values);
      synchronized (ClipAnalysis.FILE_LOCK) {
        if (isStopped()
            || !audio.isFile()
            || bytes != audio.length()
            || modified != audio.lastModified()) throw new InterruptedException();
        ClipAnalysis.writeWaveform(audio, waveform);
        if (!mode.equals("wave")) result.write(audio);
      }
    } finally {
      if (recognizer != null) recognizer.close();
      if (acquired) SpeechModels.release();
      if (detector != null) detector.close();
    }
  }

  private static void appendWords(ClipAnalysis result, String json)
      throws JSONException, IOException {
    JSONArray words = new JSONObject(json).optJSONArray("result");
    if (words == null) return;
    for (int i = 0; i < words.length(); i++) {
      if (result.words.size() >= 250000) throw new IOException("Transcript exceeds word limit");
      JSONObject word = words.getJSONObject(i);
      result.words.add(
          new ClipAnalysis.Word(
              word.getString("word"),
              Math.max(0, Math.round(word.getDouble("start") * 1000)),
              Math.max(0, Math.round(word.getDouble("end") * 1000))));
    }
  }
}
