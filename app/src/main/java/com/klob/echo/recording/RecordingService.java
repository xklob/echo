package com.klob.echo.recording;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.klob.echo.BuildConfig;
import com.klob.echo.R;
import com.klob.echo.StartupReceiver;
import com.klob.echo.core.*;
import com.klob.echo.storage.ClipStore;
import com.klob.echo.storage.Io;
import com.klob.echo.storage.TraceStore;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import java.time.ZonedDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sole owner of AudioRecord and history. Commands and capture serialize on captureHandler; exports
 * run on one bounded worker against a pinned snapshot.
 */
public final class RecordingService extends Service {
  public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".START";
  public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
  public static final String ACTION_SAVE = BuildConfig.APPLICATION_ID + ".SAVE";
  public static final String ACTION_CONTINUOUS = BuildConfig.APPLICATION_ID + ".CONTINUOUS";
  public static final String ACTION_RULES = BuildConfig.APPLICATION_ID + ".RULES";
  public static final String PREFS = BuildConfig.APPLICATION_ID;
  public static final String CHANNEL = "recording", REMINDERS = "resume";
  private final Handler main = new Handler(Looper.getMainLooper());
  private HandlerThread captureThread;
  private Handler captureHandler;
  private final ExecutorService exports = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean(), cancelled = new AtomicBoolean();
  private final java.util.concurrent.atomic.AtomicLong commandGeneration =
      new java.util.concurrent.atomic.AtomicLong();
  private AudioRecord recorder;
  private OpusHistory history;
  private final short[] pcm = new short[2400];
  private ClipStore clips;
  public CaptureTools tools;
  private ActivityDetector activityDetector;
  private boolean activityEnabled;
  private volatile int optionalTypes;
  private volatile android.media.projection.MediaProjection playbackProjection;
  private String historySource = "microphone";

  public boolean isPlaybackCapture() {
    return playbackProjection != null;
  }

  private SharedPreferences prefs;
  private volatile boolean desired, foreground, destroyed;
  private boolean silenced;
  public static volatile boolean sessionActive;
  private long sessionStartMs,
      lastReadMs,
      autoCursor,
      lastAutoElapsed,
      epoch,
      pendingContinuousEnd = -1;
  private boolean stopAfterSave;
  private long autoBudgetCursor, autoRetryAfter;
  private final java.util.ArrayDeque<String> diagnostics = new java.util.ArrayDeque<>();
  private String lastDiagnosticState = "";
  private int readFailures;
  public volatile String captureState = "Stopped", message = "", saveState = "";
  public volatile long availableSamples, memoryBytes, diskBytes, endSample, startMs;
  public volatile int progress;
  public volatile boolean continuous;
  public volatile float[] amplitudes = new float[32];
  private final float[] waveform = new float[32];
  private int waveformIndex, tickCount;
  private PowerManager.WakeLock wakeLock;

  public final class LocalBinder extends Binder {
    public RecordingService service() {
      return RecordingService.this;
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return new LocalBinder();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
    clips = new ClipStore(this);
    captureThread = new HandlerThread("Echo capture", android.os.Process.THREAD_PRIORITY_AUDIO);
    captureThread.start();
    captureHandler = new Handler(captureThread.getLooper());
    channels(this);
    wakeLock =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":capture");
    wakeLock.setReferenceCounted(false);
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_BATTERY_CHANGED);
    filter.addAction(Intent.ACTION_TIME_CHANGED);
    filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(rulesReceiver, filter, RECEIVER_NOT_EXPORTED);
    else registerReceiver(rulesReceiver, filter);
    tools = new CaptureTools(this);
    captureHandler.post(
        () -> {
          clips.cleanPartials();
          File[] tracePartials = new TraceStore(this).root.listFiles();
          if (tracePartials != null)
            for (File f : tracePartials) if (f.getName().endsWith(".part")) f.delete();
          File[] stale = new File(getFilesDir(), "history").listFiles();
          if (stale != null) for (File f : stale) if (f.getName().endsWith(".packets")) f.delete();
        });
  }

  public static void channels(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Recording status", NotificationManager.IMPORTANCE_LOW));
    manager.createNotificationChannel(
        new NotificationChannel(
            REMINDERS, "Resume listening", NotificationManager.IMPORTANCE_DEFAULT));
  }

  private final android.content.BroadcastReceiver rulesReceiver =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
          if (captureHandler != null)
            captureHandler.post(
                () -> {
                  if (desired) applyRules();
                });
        }
      };

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    String action = intent == null ? null : intent.getAction();
    if (ACTION_STOP.equals(action)) {
      stopListening();
      return START_NOT_STICKY;
    }
    if (!ACTION_START.equals(action) && !foreground) {
      if (prefs.getBoolean("enabled", false)) resumeNotification(this);
      stopSelf(id);
      return START_NOT_STICKY;
    }
    if (ACTION_START.equals(action)) commandGeneration.incrementAndGet();
    try {
      if (!foreground) {
        optionalTypes = 0;
        startForeground(
            458, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        foreground = true;
      }
    } catch (RuntimeException e) {
      message = "Open Echo and tap Start recording to allow microphone access.";
      captureState = "Microphone unavailable";
      resumeNotification(this);
      stopSelf(id);
      return START_NOT_STICKY;
    }
    captureHandler.post(
        () -> {
          if (ACTION_START.equals(action)) {
            if (stopAfterSave && busy.get()) {
              message = getString(R.string.save_pending);
              return;
            }
            stopAfterSave = false;
            desired = true;
            sessionActive = true;
            prefs.edit().putBoolean("enabled", true).apply();
            applyRules();
          } else if (ACTION_SAVE.equals(action))
            saveLookback(intent.getLongExtra("seconds", 60), "manual");
          else if (ACTION_CONTINUOUS.equals(action))
            setContinuous(intent.getBooleanExtra("enabled", false));
          else if (ACTION_RULES.equals(action) && desired) applyRules();
        });
    return START_NOT_STICKY;
  }

  public void stopListening() {
    commandGeneration.incrementAndGet();
    prefs.edit().putBoolean("enabled", false).apply();
    captureHandler.post(
        () -> {
          desired = false;
          sessionActive = false;
          main.post(tools::stop);
          closeCapture(true);
          main.post(this::releasePlayback);
          if (continuous && history != null && endSample > autoCursor) {
            if (busy.get()) pendingContinuousEnd = endSample;
            else beginSave(endSample - autoCursor, "continuous", true);
          }
          continuous = false;
          captureState = "Stopped";
          cancelAlarm();
          if (busy.get()) {
            stopAfterSave = true;
            notifyState();
          } else finishStop();
        });
  }

  private void finishStop() {
    stopAfterSave = false;
    long stoppedGeneration = commandGeneration.get();
    main.post(
        () -> {
          if (stoppedGeneration != commandGeneration.get() || desired || destroyed) return;
          foreground = false;
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf();
        });
  }

  private int batteryPercent() {
    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    if (battery == null) return -1;
    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
    return scale <= 0 ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / scale;
  }

  private void applyRules() {
    if (!desired || destroyed) return;
    int battery = batteryPercent(), minimum = prefs.getInt("battery_min", 0);
    boolean schedule = prefs.getBoolean("schedule", false);
    int from = prefs.getInt("schedule_from", 0), to = prefs.getInt("schedule_to", 0);
    if (!ListeningRules.allowed(
        desired, schedule, from, to, ZonedDateTime.now(), battery, minimum)) {
      closeCapture(true);
      main.post(tools::stop);
      captureState =
          battery >= 0 && battery < minimum ? "Paused by battery rule" : "Paused by schedule";
      notifyState();
    } else if (recorder == null) openCapture();
    scheduleAlarm();
  }

  @SuppressLint("MissingPermission")
  private void openCapture() {
    if (!desired || destroyed || recorder != null) return;
    try {
      if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
          != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("Microphone permission revoked");
      int minimum =
          AudioRecord.getMinBufferSize(
              48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("48 kHz microphone capture unavailable");
      AudioRecord.Builder builder =
          new AudioRecord.Builder()
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(48000)
                      .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(minimum * 4, 19200));
      if (playbackProjection == null)
        builder.setAudioSource(MediaRecorder.AudioSource.MIC).setPrivacySensitive(false);
      else
        builder.setAudioPlaybackCaptureConfig(
            new AudioPlaybackCaptureConfiguration.Builder(playbackProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build());
      AudioRecord next = builder.build();
      recorder = next;
      next.registerAudioRecordingCallback(
          command -> captureHandler.post(command), recordingCallback);
      long captureStartMs = System.currentTimeMillis();
      next.startRecording();
      if (next.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IOException("Microphone did not start");
      if (history != null) {
        history.close();
        history = null;
        message =
            "Audio history restarted after a gap in recording. Saved recordings are unchanged.";
      }
      long mb = Math.max(1, Math.min(maxRamMb(), prefs.getInt("ram_mb", 16)));
      long disk =
          prefs.getBoolean("disk", false)
              ? Math.max(16, Math.min(1024, prefs.getInt("disk_mb", 128))) * 1048576L
              : 0;
      historySource = playbackProjection == null ? "microphone" : "device_audio";
      history =
          new OpusHistory(
              mb * 1048576L,
              disk,
              new File(getFilesDir(), "history"),
              prefs.getInt("bitrate", 32000),
              new StatFs(getFilesDir().getPath()).getBlockSizeLong());
      epoch++;
      publish();
      activityEnabled = prefs.getBoolean("activity", false);
      activityDetector =
          new ActivityDetector(
              prefs.getInt("activity_db", -40),
              prefs.getInt("activity_pre", 2),
              prefs.getInt("activity_post", 2));
      sessionStartMs = captureStartMs;
      startMs = sessionStartMs;
      autoCursor = 0;
      autoBudgetCursor = 0;
      autoRetryAfter = 0;
      lastAutoElapsed = SystemClock.elapsedRealtime();
      lastReadMs = lastAutoElapsed;
      readFailures = 0;
      silenced = false;
      captureState = playbackProjection == null ? "Listening" : "Listening // device audio";
      wakeLock.acquire(10 * 60 * 1000L);
      captureHandler.removeCallbacks(readAudio);
      captureHandler.post(readAudio);
      prefs.edit().putBoolean("history_settings_pending", false).apply();
      main.post(tools::restoreConfigured);
      notifyState();
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      closeCapture(false);
      captureState = "Microphone unavailable";
      message =
          e instanceof OutOfMemoryError
              ? "The memory budget is too large for this device. Lower it in Settings."
              : "The microphone could not start. Check permissions and other recording apps.";
      notifyState();
      retry();
    }
  }

  private final AudioManager.AudioRecordingCallback recordingCallback =
      new AudioManager.AudioRecordingCallback() {
        @Override
        public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> configs) {
          if (recorder == null) return;
          for (AudioRecordingConfiguration config : configs)
            if (config.getClientAudioSessionId() == recorder.getAudioSessionId()) {
              boolean wasSilenced = silenced;
              silenced = config.isClientSilenced();
              if (silenced) {
                captureState = "Microphone unavailable";
                message =
                    "Another app has microphone priority. Echo will resume when it is available.";
                notifyState();
              } else if (wasSilenced) recover();
            }
        }
      };
  private final Runnable readAudio =
      new Runnable() {
        @Override
        public void run() {
          if (recorder == null || !desired || destroyed) return;
          try {
            int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
            long now = SystemClock.elapsedRealtime();
            if (count < 0 || now - lastReadMs > 5000) {
              if (count < 0) readFailures++;
              if (count == AudioRecord.ERROR_DEAD_OBJECT
                  || readFailures >= 3
                  || now - lastReadMs > 5000) {
                recover();
                return;
              }
            } else if (count > 0) {
              lastReadMs = now;
              readFailures = 0;
              if (!silenced) {
                history.write(pcm, count);
                long square = 0;
                for (int i = 0; i < count; i++) square += (long) pcm[i] * pcm[i];
                waveform[waveformIndex++ % waveform.length] =
                    (float) Math.sqrt(square / (double) count) / 32768;
                float[] ordered = new float[waveform.length];
                for (int i = 0; i < waveform.length; i++)
                  ordered[i] = waveform[(waveformIndex + i) % waveform.length];
                amplitudes = ordered;
                captureState =
                    playbackProjection == null ? "Listening" : "Listening // device audio";
                publish();
                tools.pcm(pcm, count);
                if (activityEnabled) {
                  ActivityDetector.Event event =
                      activityDetector.process(Math.sqrt(square / (double) count), count);
                  if (event != null) beginSaveRange(event.start, event.end, "activity", false);
                }
              }
            }
            if (++tickCount % 50 == 0) {
              maybeAutoSave(now);
              if (wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
            }
            captureHandler.postDelayed(this, count == pcm.length ? 1 : 20);
          } catch (IOException | RuntimeException | OutOfMemoryError e) {
            message = "Recording was interrupted. Echo is trying to reconnect.";
            recover();
          }
        }
      };

  private void publish() {
    if (history == null) return;
    availableSamples = history.availableSamples();
    memoryBytes = history.memoryBytes();
    diskBytes = history.diskBytes();
    endSample = history.endSample();
  }

  private void recover() {
    closeCapture(true);
    captureState = "Microphone unavailable";
    notifyState();
    retry();
  }

  private void retry() {
    captureHandler.removeCallbacks(retryCapture);
    if (desired && !destroyed) captureHandler.postDelayed(retryCapture, 3000);
  }

  private final Runnable retryCapture = this::applyRules;

  private void closeCapture(boolean flush) {
    captureHandler.removeCallbacks(readAudio);
    captureHandler.removeCallbacks(retryCapture);
    if (recorder != null) {
      try {
        recorder.unregisterAudioRecordingCallback(recordingCallback);
        recorder.stop();
      } catch (RuntimeException ignored) {
      }
      recorder.release();
      recorder = null;
    }
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    if (history != null && flush)
      try {
        history.finish();
        publish();
      } catch (IOException | RuntimeException | OutOfMemoryError e) {
        message =
            "The last audio segment could not be completed. Earlier saved clips are unchanged.";
      }
  }

  public boolean isCapturing() {
    return foreground && desired && captureState.startsWith("Listening");
  }

  public boolean isSaving() {
    return busy.get();
  }

  public void cancelSave() {
    cancelled.set(true);
    captureHandler.post(() -> pendingContinuousEnd = -1);
  }

  public boolean isSessionActive() {
    return desired && foreground;
  }

  public boolean isEnabled() {
    return prefs.getBoolean("enabled", false);
  }

  public static int maxRamMb() {
    return (int) Math.max(1, Math.min(64, Runtime.getRuntime().maxMemory() / 4 / 1048576));
  }

  public void saveLookback(long seconds, String kind) {
    captureHandler.post(
        () -> {
          if (history != null)
            beginSave(Math.max(1, Math.min(86400, seconds)) * 48000L, kind, false);
          else message = getString(R.string.history_empty);
        });
  }

  public void setContinuous(boolean enabled) {
    captureHandler.post(
        () -> {
          if (continuous && !enabled && history != null && endSample > autoCursor) {
            if (busy.get()) pendingContinuousEnd = endSample;
            else beginSave(endSample - autoCursor, "continuous", true);
          }
          continuous = enabled && desired;
          if (continuous) {
            lastAutoElapsed = SystemClock.elapsedRealtime();
            autoCursor = endSample;
            autoBudgetCursor = history == null ? 0 : history.writtenBudgetBytes();
          }
        });
  }

  private void maybeAutoSave(long now) {
    if (history == null || busy.get() || now < autoRetryAfter) return;
    int interval = Math.max(10, Math.min(3600, prefs.getInt("autosave_seconds", 300)));
    boolean byTime = now - lastAutoElapsed >= interval * 1000L;
    boolean byFill =
        prefs.getBoolean("autosave_fill", false)
            && history.fillSince(autoBudgetCursor) >= .75
            && now - lastAutoElapsed >= 10000;
    if ((continuous || prefs.getBoolean("autosave", false))
        && (byTime || byFill)
        && endSample > autoCursor)
      beginSave(endSample - autoCursor, continuous ? "continuous" : "auto", true);
  }

  private void beginSave(long samples, String kind, boolean advanceCursor) {
    beginSaveRange(
        Math.max(0, history.endSample() - samples), history.endSample(), kind, advanceCursor);
  }

  private void beginSaveRange(long from, long to, String kind, boolean advanceCursor) {
    if (!busy.compareAndSet(false, true)) {
      message = "A recording is already being saved. Wait for it to finish.";
      return;
    }
    cancelled.set(false);
    progress = 0;
    saveState = "Saving";
    final OpusHistory.Snapshot snapshot;
    final long wallStart;
    final String snapshotSource = historySource;
    final String format = prefs.getString("format", "opus"), tree = prefs.getString("tree", "");
    try {
      snapshot = history.snapshotRange(from, to);
      wallStart = sessionStartMs + snapshot.startSample * 1000 / 48000;
    } catch (IOException | RuntimeException e) {
      busy.set(false);
      saveState = "";
      message = "This audio is no longer in history. Choose a shorter clip and try again.";
      return;
    }
    final long end = snapshot.endSample,
        savedEpoch = epoch,
        savedBudget = history.writtenBudgetBytes();
    notifyState();
    exports.execute(
        () -> {
          File partial = null, completed = null;
          try (OpusHistory.Snapshot ignored = snapshot) {
            partial = clips.temporary(wallStart, kind, format);
            if (format.equals("opus"))
              ClipEncoder.opus(snapshot, partial, cancelled::get, p -> progress = p);
            else if (format.equals("wav"))
              ClipEncoder.wav(snapshot, partial, cancelled::get, p -> progress = p);
            else
              PlatformEncoder.export(
                  snapshot, partial, format.equals("flac"), cancelled::get, p -> progress = p);
            if (cancelled.get()) throw new CancellationException();
            completed = clips.complete(partial);
            partial = null;
            snapshot.close();
            clips.rotate(kind, prefs.getInt("auto_keep", 48), completed);
            org.json.JSONObject metadata = new org.json.JSONObject();
            metadata.put("start_time_ms", wallStart);
            metadata.put("sample_rate", 48000);
            metadata.put("captured_samples", snapshot.sampleCount);
            metadata.put("duration_seconds", snapshot.sampleCount / 48000.0);
            metadata.put("history_codec", "opus");
            metadata.put("source", snapshotSource);
            metadata.put("format", format);
            metadata.put("kind", kind);
            File sidecar = new File(completed.getPath() + ".json");
            File metadataPartial = new File(sidecar.getPath() + ".part");
            Io.write(metadataPartial.toPath(), metadata.toString(2));
            java.nio.file.Files.move(
                metadataPartial.toPath(),
                sidecar.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            if (!tree.isEmpty()) {
              clips.copyToTree(completed, tree, cancelled::get);
              clips.copyToTree(sidecar, tree, cancelled::get);
            }
            progress = 100;
            message = "Saved recording to Library.";
          } catch (CancellationException e) {
            message =
                completed == null
                    ? "Save cancelled."
                    : "Folder copy cancelled; local recording kept.";
          } catch (Exception | OutOfMemoryError e) {
            message =
                completed != null
                    ? "Recording kept in Library. The additional folder copy failed; check folder"
                        + " access and free space."
                    : "The recording could not be saved. Check free storage and try a shorter"
                        + " clip.";
          } finally {
            if (partial != null) partial.delete();
            saveState = "";
            final boolean locallySaved = completed != null;
            captureHandler.post(
                () -> {
                  busy.set(false);
                  if (advanceCursor && epoch == savedEpoch) {
                    if (locallySaved) {
                      autoCursor = end;
                      autoBudgetCursor = savedBudget;
                      lastAutoElapsed = SystemClock.elapsedRealtime();
                      autoRetryAfter = 0;
                    } else autoRetryAfter = SystemClock.elapsedRealtime() + 30000;
                  }
                  if (pendingContinuousEnd > autoCursor && history != null && savedEpoch == epoch) {
                    long finalEnd = pendingContinuousEnd;
                    pendingContinuousEnd = -1;
                    beginSaveRange(autoCursor, finalEnd, "continuous", true);
                  }
                  if (stopAfterSave && !busy.get()) finishStop();
                  else notifyState();
                });
          }
        });
  }

  public void usePlaybackCapture(int resultCode, Intent consent) {
    if (tools.screen.isRunning()) {
      message = "Stop screenshots before switching audio sources.";
      return;
    }
    if (!promoteOptional(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)) return;
    try {
      android.media.projection.MediaProjection projection =
          getSystemService(android.media.projection.MediaProjectionManager.class)
              .getMediaProjection(resultCode, consent);
      if (projection == null) {
        message = "Device audio consent was not granted.";
        return;
      }
      projection.registerCallback(
          new android.media.projection.MediaProjection.Callback() {
            @Override
            public void onStop() {
              if (playbackProjection == projection) stopListening();
            }
          },
          main);
      captureHandler.post(
          () -> {
            closeCapture(true);
            playbackProjection = projection;
            openCapture();
          });
    } catch (RuntimeException e) {
      message = "Device audio capture unavailable; microphone capture unchanged.";
    }
  }

  private void releasePlayback() {
    android.media.projection.MediaProjection projection = playbackProjection;
    playbackProjection = null;
    if (projection != null) projection.stop();
  }

  public void useMicrophoneCapture() {
    captureHandler.post(
        () -> {
          closeCapture(true);
          android.media.projection.MediaProjection previous = playbackProjection;
          playbackProjection = null;
          if (previous != null) main.post(previous::stop);
          openCapture();
        });
  }

  public boolean promoteOptional(int type) {
    if (!foreground || !prefs.getBoolean("enabled", false)) {
      message = getString(R.string.needs_recording);
      return false;
    }
    try {
      startForeground(
          458,
          notification(captureState),
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | optionalTypes | type);
      optionalTypes |= type;
      return true;
    } catch (RuntimeException e) {
      message = "Permission or foreground access required for this tool.";
      return false;
    }
  }

  public void refreshOptionalTypes() {
    if (tools == null || !foreground) return;
    int types =
        (tools.gps ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0)
            | (tools.camera.isRunning() ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA : 0)
            | (tools.screen.isRunning() || playbackProjection != null
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : 0);
    optionalTypes = types;
    try {
      startForeground(
          458, notification(captureState), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | types);
    } catch (RuntimeException ignored) {
    }
  }

  private Notification notification(String state) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, RecordingService.class).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE);
    PendingIntent save =
        PendingIntent.getService(
            this,
            2,
            new Intent(this, RecordingService.class)
                .setAction(ACTION_SAVE)
                .putExtra("seconds", 60L),
            PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_notify_recording)
        .setContentTitle(
            "Echo // "
                + (state.startsWith("Listening") ? getString(R.string.recording) : state)
                + (busy.get() ? " // saving" : "")
                + (tools == null ? "" : tools.summary()))
        .setContentText(getString(R.string.notification_controls))
        .setContentIntent(open)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .addAction(0, getString(R.string.notification_save), save)
        .addAction(0, getString(R.string.notification_stop), stop)
        .build();
  }

  public synchronized String diagnosticReport() {
    return "Echo "
        + BuildConfig.VERSION_NAME
        + " ("
        + BuildConfig.VERSION_CODE
        + ")\n"
        + "Android API "
        + Build.VERSION.SDK_INT
        + "\nState: "
        + captureState
        + "\nBuffered samples: "
        + availableSamples
        + "\nHistory RAM bytes: "
        + memoryBytes
        + "\nHistory disk bytes: "
        + diskBytes
        + "\nHeap limit: "
        + Runtime.getRuntime().maxMemory()
        + "\n"
        + String.join("\n", diagnostics);
  }

  private synchronized void recordDiagnosticState() {
    if (captureState.equals(lastDiagnosticState)) return;
    lastDiagnosticState = captureState;
    diagnostics.addLast(java.time.Instant.now() + " " + captureState);
    while (diagnostics.size() > 100) diagnostics.removeFirst();
  }

  private void notifyState() {
    recordDiagnosticState();
    main.post(
        () -> {
          if (foreground)
            getSystemService(NotificationManager.class).notify(458, notification(captureState));
        });
  }

  public static void resumeNotification(Context context) {
    channels(context);
    PendingIntent open =
        PendingIntent.getActivity(
            context, 3, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    try {
      context
          .getSystemService(NotificationManager.class)
          .notify(
              459,
              new NotificationCompat.Builder(context, REMINDERS)
                  .setSmallIcon(R.drawable.ic_stat_notify_recording)
                  .setContentTitle(context.getString(R.string.notification_resume))
                  .setContentText(context.getString(R.string.resume_notice))
                  .setContentIntent(open)
                  .setAutoCancel(true)
                  .build());
    } catch (SecurityException ignored) {
    }
  }

  private PendingIntent alarmIntent() {
    return PendingIntent.getBroadcast(
        this,
        4,
        new Intent(this, StartupReceiver.class).setAction(ACTION_RULES),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private void cancelAlarm() {
    getSystemService(AlarmManager.class).cancel(alarmIntent());
  }

  private void scheduleAlarm() {
    cancelAlarm();
    if (!prefs.getBoolean("schedule", false) || !desired) return;
    long time =
        ListeningRules.nextTransition(
            prefs.getInt("schedule_from", 0), prefs.getInt("schedule_to", 0), ZonedDateTime.now());
    AlarmManager alarms = getSystemService(AlarmManager.class);
    try {
      if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent());
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent());
    } catch (SecurityException revoked) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent());
    }
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    sessionActive = false;
    cancelled.set(true);
    releasePlayback();
    if (tools != null) tools.close();
    unregisterReceiver(rulesReceiver);
    cancelAlarm();
    captureHandler.post(
        () -> {
          desired = false;
          closeCapture(false);
          try {
            if (history != null) history.close();
          } catch (IOException ignored) {
          }
          captureThread.quitSafely();
        });
    exports.shutdown();
    super.onDestroy();
  }
}
