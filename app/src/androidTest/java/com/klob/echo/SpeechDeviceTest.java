package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.*;
import com.klob.echo.settings.AppSettings;
import com.klob.echo.speech.*;
import com.klob.echo.storage.*;
import com.klob.echo.tools.ModelInstaller;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SpeechDeviceTest {
  private Context context;

  @Before
  public void setup() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSettings.prefs(context)
        .edit()
        .putBoolean("auto_analyze_speech", false)
        .putBoolean("auto_transcribe", false)
        .commit();
  }

  private File fixture(boolean spoken) throws Exception {
    File directory = new File(context.getFilesDir(), "clips/manual");
    directory.mkdirs();
    File file = File.createTempFile("speech-fixture-", ".wav", directory);
    byte[] pcm =
        spoken
            ? Io.readBytes(
                InstrumentationRegistry.getInstrumentation()
                    .getContext()
                    .getAssets()
                    .open("spoken-digits.pcm"),
                2 * 1048576)
            : new byte[48000 * 2 * 3];
    int padding = 48000 * 2;
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    int bytes = pcm.length + padding * 2;
    header
        .put("RIFF".getBytes())
        .putInt(bytes + 36)
        .put("WAVEfmt ".getBytes())
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(48000)
        .putInt(96000)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes())
        .putInt(bytes);
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(header.array());
      out.write(new byte[padding]);
      out.write(pcm);
      out.write(new byte[padding]);
    }
    return file;
  }

  private void await(File file) throws Exception {
    long end = SystemClock.elapsedRealtime() + 120000;
    while (SystemClock.elapsedRealtime() < end) {
      List<WorkInfo> jobs =
          WorkManager.getInstance(context)
              .getWorkInfosByTag(AnalysisWorker.tag(file))
              .get(5, TimeUnit.SECONDS);
      if (!jobs.isEmpty() && jobs.stream().allMatch(w -> w.getState().isFinished())) {
        for (WorkInfo job : jobs)
          assertEquals(job.getOutputData().toString(), WorkInfo.State.SUCCEEDED, job.getState());
        return;
      }
      Thread.sleep(100);
    }
    fail("Clip analysis timed out");
  }

  @Test
  public void offlineSpeechAndTranscriptionProduceTimedDurableResults() throws Exception {
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      File file = fixture(true);
      long bytes = file.length(), modified = file.lastModified();
      AnalysisWorker.enqueue(context, file, "speech");
      await(file);
      ClipAnalysis result = ClipAnalysis.read(file);
      assertTrue(result.analyzed);
      assertFalse(result.transcribed);
      assertFalse("Natural spoken digits should be detected as speech", result.speech.isEmpty());
      assertTrue(result.speech.get(0)[0] >= 500);
      for (long[] span : result.speech) assertTrue(span[0] < span[1] && span[1] <= result.duration);
      ClipAnalysis.Waveform waveform = ClipAnalysis.readWaveform(file);
      assertNotNull(waveform);
      assertTrue(waveform.lows.length > 900);
      assertEquals(160, waveform.width);
      ModelInstaller.install(context);
      AnalysisWorker.enqueue(context, file, "transcript");
      await(file);
      result = ClipAnalysis.read(file);
      assertTrue(result.transcribed);
      assertTrue(result.transcript(), result.transcript().contains("one"));
      assertFalse(result.words.isEmpty());
      long previous = -1;
      for (ClipAnalysis.Word word : result.words) {
        assertTrue(word.start >= previous && word.end >= word.start);
        assertTrue(word.end <= result.duration + 100);
        previous = word.start;
      }
      assertEquals(
          result.transcript(), Io.read(ClipAnalysis.file(file, ".transcript.txt").toPath()));
      assertEquals(bytes, file.length());
      assertEquals(modified, file.lastModified());
      assertTrue(
          new org.json.JSONObject(Io.read(new File(file.getPath() + ".json").toPath()))
              .getBoolean("transcribed"));
    }
  }

  @Test
  public void automaticOptionsAreIndependentAndSilenceIsNotSpeech() throws Exception {
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      File disabled = fixture(false);
      AnalysisWorker.saved(context, disabled);
      assertTrue(
          WorkManager.getInstance(context)
              .getWorkInfosByTag(AnalysisWorker.tag(disabled))
              .get()
              .isEmpty());
      AppSettings.save(context, "auto_analyze_speech", true, false);
      File analyzed = fixture(false);
      AnalysisWorker.saved(context, analyzed);
      await(analyzed);
      ClipAnalysis result = ClipAnalysis.read(analyzed);
      assertTrue(result.analyzed);
      assertTrue(result.speech.isEmpty());
      assertFalse(result.transcribed);
      ModelInstaller.install(context);
      AppSettings.save(context, "auto_analyze_speech", false, false);
      AppSettings.save(context, "auto_transcribe", true, false);
      File transcribed = fixture(true);
      AnalysisWorker.saved(context, transcribed);
      await(transcribed);
      result = ClipAnalysis.read(transcribed);
      assertTrue(result.transcribed);
      assertTrue(result.analyzed);
      assertFalse(result.words.isEmpty());
    }
  }

  @Test
  public void waveformOnlyDoesNotRunSpeechOrTranscription() throws Exception {
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      File file = fixture(true);
      AnalysisWorker.enqueue(context, file, "wave");
      await(file);
      assertNotNull(ClipAnalysis.readWaveform(file));
      assertFalse(ClipAnalysis.file(file, ".analysis.json").exists());
      assertFalse(ClipAnalysis.file(file, ".transcript.txt").exists());
    }
  }

  @Test
  public void compressedSavedFormatsKeepSpeechTiming() throws Exception {
    byte[] source;
    try (InputStream in =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("spoken-digits.pcm")) {
      source = Io.readBytes(in, 2 * 1048576);
    }
    try (com.klob.echo.core.OpusHistory history =
        new com.klob.echo.core.OpusHistory(1048576, 0, context.getCacheDir(), 32000)) {
      ByteBuffer bytes = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
      short[] pcm = new short[2400];
      while (bytes.hasRemaining()) {
        int count = Math.min(pcm.length, bytes.remaining() / 2);
        for (int i = 0; i < count; i++) pcm[i] = bytes.getShort();
        history.write(pcm, count);
      }
      history.finish();
      try (com.klob.echo.core.OpusHistory.Snapshot snapshot = history.snapshot(Long.MAX_VALUE)) {
        for (String format : new String[] {"opus", "flac", "m4a"}) {
          File output = File.createTempFile("speech-format-", "." + format, context.getCacheDir());
          if (format.equals("opus"))
            com.klob.echo.core.ClipEncoder.opus(snapshot, output, () -> false, p -> {});
          else
            com.klob.echo.recording.PlatformEncoder.export(
                snapshot, output, format.equals("flac"), () -> false, p -> {});
          try (SpeechDetector detector = new SpeechDetector(context)) {
            long[] samples = {0};
            AudioDecoder.decode(
                output,
                (decoded, count) -> {
                  samples[0] += count;
                  detector.accept(decoded, count);
                });
            assertTrue(
                format, Math.abs(samples[0] * 1000 / 16000 - source.length * 1000L / 96000) < 100);
            assertFalse(format, detector.finish().isEmpty());
          } finally {
            output.delete();
          }
        }
      }
    }
  }
}
