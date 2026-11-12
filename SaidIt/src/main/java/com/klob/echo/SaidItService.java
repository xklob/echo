package com.klob.echo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.klob.echo.core.*;
import java.io.*;
import java.time.ZonedDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sole owner of AudioRecord and history. Commands and capture serialize on captureHandler;
 * exports run on one bounded worker against a pinned snapshot. */
public final class SaidItService extends Service {
    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".START";
    public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    public static final String ACTION_SAVE = BuildConfig.APPLICATION_ID + ".SAVE";
    public static final String ACTION_CONTINUOUS = BuildConfig.APPLICATION_ID + ".CONTINUOUS";
    public static final String ACTION_RULES = BuildConfig.APPLICATION_ID + ".RULES";
    static final String PREFS = BuildConfig.APPLICATION_ID;
    static final String CHANNEL = "recording", REMINDERS = "resume";
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread captureThread;
    private Handler captureHandler;
    private final ExecutorService exports = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(), cancelled = new AtomicBoolean();
    private AudioRecord recorder;
    private OpusHistory history;
    private final short[] pcm = new short[2400];
    private ClipStore clips;
    private SharedPreferences prefs;
    private boolean desired, foreground, silenced, interrupted, destroyed;
    private volatile boolean accepting = true;
    private long sessionStartMs, lastReadMs, autoCursor, lastAutoElapsed;
    private int readFailures;
    public volatile String captureState = "Stopped", message = "", saveState = "";
    public volatile long availableSamples, memoryBytes, diskBytes, endSample, startMs;
    public volatile int progress;
    public volatile boolean continuous;
    public volatile float[] amplitudes = new float[32];
    private final float[] waveform = new float[32];
    private int waveformIndex, tickCount;
    private PowerManager.WakeLock wakeLock;

    public final class LocalBinder extends Binder { SaidItService service() { return SaidItService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public void onCreate() {
        super.onCreate(); prefs = getSharedPreferences(PREFS, MODE_PRIVATE); clips = new ClipStore(this);
        captureThread = new HandlerThread("Echo capture", android.os.Process.THREAD_PRIORITY_AUDIO); captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        channels(this);
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":capture");
        wakeLock.setReferenceCounted(false);
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_BATTERY_CHANGED); filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rulesReceiver, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(rulesReceiver, filter);
        captureHandler.post(clips::cleanPartials);
    }
    static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording status", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(REMINDERS, "Resume listening", NotificationManager.IMPORTANCE_DEFAULT));
    }
    private final android.content.BroadcastReceiver rulesReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (captureHandler != null) captureHandler.post(() -> { if (desired) applyRules(); }); }
    };
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopListening(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(action) && !foreground) { stopSelf(id); return START_NOT_STICKY; }
        try {
            if (!foreground) { startForeground(458, notification("Starting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); foreground = true; }
        } catch (RuntimeException e) {
            message = "Open Echo and tap LISTEN to grant microphone access."; captureState = "Microphone unavailable"; resumeNotification(this); stopSelf(id); return START_NOT_STICKY;
        }
        captureHandler.post(() -> {
            if (ACTION_START.equals(action)) {
                desired = true; prefs.edit().putBoolean("enabled", true).apply(); applyRules();
            } else if (ACTION_SAVE.equals(action)) saveLookback(intent.getLongExtra("seconds", 60), "manual");
            else if (ACTION_CONTINUOUS.equals(action)) setContinuous(intent.getBooleanExtra("enabled", false));
            else if (ACTION_RULES.equals(action) && desired) applyRules();
        });
        return START_NOT_STICKY;
    }
    public void stopListening() {
        prefs.edit().putBoolean("enabled", false).apply();
        captureHandler.post(() -> {
            desired = false; closeCapture(true);
            if (continuous && history != null && endSample > autoCursor && !busy.get()) beginSave(endSample - autoCursor, "continuous", true);
            continuous = false; captureState = "Stopped";
            cancelAlarm(); main.post(() -> { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
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
        if (!ListeningRules.allowed(desired, schedule, from, to, ZonedDateTime.now(), battery, minimum)) {
            closeCapture(true); captureState = battery >= 0 && battery < minimum ? "Paused by battery rule" : "Paused by schedule";
            notifyState();
        } else if (recorder == null) openCapture();
        scheduleAlarm();
    }
    @SuppressLint("MissingPermission") private void openCapture() {
        if (!desired || destroyed || recorder != null) return;
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Microphone permission revoked");
            int minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("48 kHz microphone capture unavailable");
            AudioRecord next = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum * 4, 19200)).setPrivacySensitive(false).build();
            recorder = next;
            next.registerAudioRecordingCallback(command -> captureHandler.post(command), recordingCallback);
            next.startRecording();
            if (next.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start");
            if (history != null) { history.close(); history = null; message = "History restarted after a capture gap."; }
            long mb = Math.max(1, Math.min(maxRamMb(), prefs.getInt("ram_mb", 16)));
            long disk = prefs.getBoolean("disk", false) ? Math.max(16, Math.min(1024, prefs.getInt("disk_mb", 128))) * 1048576L : 0;
            history = new OpusHistory(mb * 1048576L, disk, new File(getCacheDir(), "history"), prefs.getInt("bitrate", 32000));
            sessionStartMs = System.currentTimeMillis(); startMs = sessionStartMs; autoCursor = 0; lastAutoElapsed = SystemClock.elapsedRealtime();
            lastReadMs = lastAutoElapsed; readFailures = 0; interrupted = false; silenced = false;
            captureState = "Listening";
            wakeLock.acquire(10 * 60 * 1000L);
            captureHandler.removeCallbacks(readAudio); captureHandler.post(readAudio); notifyState();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            closeCapture(false); captureState = "Microphone unavailable"; message = e instanceof OutOfMemoryError ? "Not enough heap for the selected history budget." : "Capture failed: " + e.getClass().getSimpleName();
            notifyState(); retry();
        }
    }
    private final AudioManager.AudioRecordingCallback recordingCallback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(java.util.List<AudioRecordingConfiguration> configs) {
            if (recorder == null) return;
            for (AudioRecordingConfiguration config : configs) if (config.getClientAudioSessionId() == recorder.getAudioSessionId()) {
                boolean wasSilenced = silenced; silenced = config.isClientSilenced();
                if (silenced) { interrupted = true; captureState = "Microphone unavailable"; message = "Android silenced this microphone stream."; notifyState(); }
                else if (wasSilenced) recover();
            }
        }
    };
    private final Runnable readAudio = new Runnable() {
        @Override public void run() {
            if (recorder == null || !desired || destroyed) return;
            try {
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                long now = SystemClock.elapsedRealtime();
                if (count < 0 || now - lastReadMs > 5000) {
                    if (count < 0) readFailures++;
                    if (count == AudioRecord.ERROR_DEAD_OBJECT || readFailures >= 3 || now - lastReadMs > 5000) { recover(); return; }
                } else if (count > 0) {
                    lastReadMs = now; readFailures = 0;
                    if (!silenced) {
                        history.write(pcm, count);
                        long square = 0; for (int i = 0; i < count; i++) square += (long) pcm[i] * pcm[i];
                        waveform[waveformIndex++ % waveform.length] = (float) Math.sqrt(square / (double) count) / 32768;
                        float[] ordered = new float[waveform.length];
                        for (int i = 0; i < waveform.length; i++) ordered[i] = waveform[(waveformIndex + i) % waveform.length];
                        amplitudes = ordered; captureState = "Listening";
                        publish();
                    }
                }
                if (++tickCount % 50 == 0) {
                    maybeAutoSave(now); if (wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 1000L);
                }
                captureHandler.postDelayed(this, count == pcm.length ? 1 : 20);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                message = "Capture interrupted: " + e.getClass().getSimpleName(); recover();
            }
        }
    };
    private void publish() {
        if (history == null) return;
        availableSamples = history.availableSamples(); memoryBytes = history.memoryBytes(); diskBytes = history.diskBytes(); endSample = history.endSample();
    }
    private void recover() {
        interrupted = true; closeCapture(true); captureState = "Microphone unavailable"; notifyState(); retry();
    }
    private void retry() { captureHandler.removeCallbacks(retryCapture); if (desired && !destroyed) captureHandler.postDelayed(retryCapture, 3000); }
    private final Runnable retryCapture = this::applyRules;
    private void closeCapture(boolean flush) {
        captureHandler.removeCallbacks(readAudio); captureHandler.removeCallbacks(retryCapture);
        if (recorder != null) {
            try { recorder.unregisterAudioRecordingCallback(recordingCallback); recorder.stop(); } catch (RuntimeException ignored) {}
            recorder.release(); recorder = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (history != null && flush) try { history.finish(); publish(); } catch (IOException | RuntimeException | OutOfMemoryError e) { message = "Could not flush the final audio frame."; }
    }
    public boolean isSaving() { return busy.get(); }
    public void cancelSave() { cancelled.set(true); }
    public boolean isEnabled() { return prefs.getBoolean("enabled", false); }
    static int maxRamMb() { return (int) Math.max(1, Math.min(64, Runtime.getRuntime().maxMemory() / 4 / 1048576)); }
    public void saveLookback(long seconds, String kind) { captureHandler.post(() -> { if (history != null) beginSave(Math.max(1, Math.min(86400, seconds)) * 48000L, kind, false); else message = "No captured history yet."; }); }
    public void setContinuous(boolean enabled) { captureHandler.post(() -> {
        if (continuous && !enabled && history != null && endSample > autoCursor && !busy.get()) beginSave(endSample - autoCursor, "continuous", true);
        continuous = enabled && desired;
        if (continuous) { lastAutoElapsed = SystemClock.elapsedRealtime(); autoCursor = endSample; }
    }); }
    private void maybeAutoSave(long now) {
        if (history == null || busy.get()) return;
        int interval = Math.max(10, Math.min(3600, prefs.getInt("autosave_seconds", 300)));
        boolean byTime = now - lastAutoElapsed >= interval * 1000L;
        boolean byFill = prefs.getBoolean("autosave_fill", false) && history.fillRatio() >= .75 && now - lastAutoElapsed >= 10000;
        if ((continuous || prefs.getBoolean("autosave", false)) && (byTime || byFill) && endSample > autoCursor)
            beginSave(endSample - autoCursor, continuous ? "continuous" : "auto", true);
    }
    private void beginSave(long samples, String kind, boolean advanceCursor) {
        if (!busy.compareAndSet(false, true)) { message = "A save is already running."; return; }
        cancelled.set(false); progress = 0; saveState = "Saving";
        final OpusHistory.Snapshot snapshot;
        final long wallStart;
        final String format = prefs.getString("format", "opus"), tree = prefs.getString("tree", "");
        try { snapshot = history.snapshot(samples); wallStart = sessionStartMs + snapshot.startSample * 1000 / 48000; }
        catch (IOException | RuntimeException e) { busy.set(false); saveState = ""; message = e.getMessage(); return; }
        final long end = snapshot.endSample;
        exports.execute(() -> {
            File partial = null, completed = null;
            try (OpusHistory.Snapshot ignored = snapshot) {
                partial = clips.temporary(wallStart, kind, format);
                if (format.equals("opus")) ClipEncoder.opus(snapshot, partial, cancelled::get, p -> progress = p);
                else if (format.equals("wav")) ClipEncoder.wav(snapshot, partial, cancelled::get, p -> progress = p);
                else PlatformEncoder.export(snapshot, partial, format.equals("flac"), cancelled::get, p -> progress = p);
                if (cancelled.get()) throw new CancellationException();
                completed = clips.complete(partial); partial = null;
                if (!tree.isEmpty()) clips.copyToTree(completed, tree, cancelled::get);
                clips.rotate(kind, prefs.getInt("auto_keep", 48));
                progress = 100; message = "Saved " + completed.getName();
                captureHandler.post(() -> { if (advanceCursor) { autoCursor = end; lastAutoElapsed = SystemClock.elapsedRealtime(); } });
            } catch (CancellationException e) { message = completed == null ? "Save cancelled." : "Folder copy cancelled; local recording kept."; }
            catch (Exception | OutOfMemoryError e) { message = completed != null ? "Local recording kept. " + e.getMessage() : "Save failed: " + e.getClass().getSimpleName() + ". " + e.getMessage(); }
            finally { if (partial != null) partial.delete(); saveState = ""; busy.set(false); }
        });
    }
    private Notification notification(String state) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SaidItActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SaidItService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent save = PendingIntent.getService(this, 2, new Intent(this, SaidItService.class).setAction(ACTION_SAVE).putExtra("seconds", 60L), PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_notify_recording).setContentTitle("Echo // " + state)
            .setContentText("Tap to open controls").setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0, "SAVE 1 MIN", save).addAction(0, "STOP", stop).build();
    }
    private void notifyState() { main.post(() -> { if (foreground) getSystemService(NotificationManager.class).notify(458, notification(captureState)); }); }
    static void resumeNotification(Context context) {
        channels(context);
        PendingIntent open = PendingIntent.getActivity(context, 3, new Intent(context, SaidItActivity.class), PendingIntent.FLAG_IMMUTABLE);
        try { context.getSystemService(NotificationManager.class).notify(459, new NotificationCompat.Builder(context, REMINDERS).setSmallIcon(R.drawable.ic_stat_notify_recording)
            .setContentTitle("Echo // tap to resume").setContentText("Open Echo and tap LISTEN.").setContentIntent(open).setAutoCancel(true).build()); } catch (SecurityException ignored) {}
    }
    private PendingIntent alarmIntent() { return PendingIntent.getBroadcast(this, 4, new Intent(this, BroadcastReceiver.class).setAction(ACTION_RULES), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); }
    private void cancelAlarm() { getSystemService(AlarmManager.class).cancel(alarmIntent()); }
    private void scheduleAlarm() {
        cancelAlarm(); if (!prefs.getBoolean("schedule", false) || !desired) return;
        long time = ListeningRules.nextTransition(prefs.getInt("schedule_from", 0), prefs.getInt("schedule_to", 0), ZonedDateTime.now());
        AlarmManager alarms = getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent());
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, alarmIntent());
    }
    @Override public void onDestroy() {
        destroyed = true; cancelled.set(true); unregisterReceiver(rulesReceiver); cancelAlarm();
        captureHandler.post(() -> {
            desired = false; closeCapture(false);
            try { if (history != null) history.close(); } catch (IOException ignored) {}
            captureThread.quitSafely();
        });
        exports.shutdown(); super.onDestroy();
    }
}
