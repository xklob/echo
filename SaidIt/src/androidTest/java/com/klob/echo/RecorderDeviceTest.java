package com.klob.echo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import android.content.*;
import android.media.*;
import android.os.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import com.klob.echo.core.*;
import java.io.*;
import java.util.concurrent.*;

@RunWith(AndroidJUnit4.class)
public class RecorderDeviceTest {
    private Context context;
    private static void shell(String command) throws Exception {
        try (ParcelFileDescriptor p = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command); InputStream in = new FileInputStream(p.getFileDescriptor())) { while (in.read() != -1) {} }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + 15000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        assertTrue("Timed out waiting for state", condition.getAsBoolean());
    }
    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        shell("pm grant " + context.getPackageName() + " android.permission.RECORD_AUDIO");
        shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        context.getSharedPreferences(SaidItService.PREFS, 0).edit().clear().commit();
    }
    @Test public void platformExportsAndCollisions() throws Exception {
        File folder = new File(context.getFilesDir(), "validation"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (OpusHistory history = new OpusHistory(1048576, 0, context.getCacheDir(), 32000)) {
            short[] samples = new short[960];
            for (int f = 0; f < 103; f++) {
                for (int i = 0; i < samples.length; i++) samples[i] = (short) (16000 * Math.sin(2 * Math.PI * 440 * (f * 960 + i) / 48000));
                history.write(samples, samples.length);
            }
            history.write(new short[37], 37); history.finish();
            try (OpusHistory.Snapshot snapshot = history.snapshot(Long.MAX_VALUE)) {
                ClipEncoder.opus(snapshot, new File(folder, "tone.opus"), () -> false, p -> {});
                ClipEncoder.wav(snapshot, new File(folder, "tone.wav"), () -> false, p -> {});
                for (boolean flac : new boolean[]{true, false}) {
                    if (!PlatformEncoder.supported(flac ? MediaFormat.MIMETYPE_AUDIO_FLAC : MediaFormat.MIMETYPE_AUDIO_AAC)) continue;
                    File file = new File(folder, flac ? "tone.flac" : "tone.m4a");
                    PlatformEncoder.export(snapshot, file, flac, () -> false, p -> {});
                    MediaExtractor extractor = new MediaExtractor();
                    try { extractor.setDataSource(file.getPath()); assertEquals(1, extractor.getTrackCount()); assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME).startsWith("audio/")); }
                    finally { extractor.release(); }
                }
                ClipStore store = new ClipStore(context);
                File a = store.temporary(0, "manual", "wav"), b = store.temporary(0, "manual", "wav");
                assertNotEquals(a.getName(), b.getName()); assertFalse(store.list().contains(a)); a.delete(); b.delete();
                File corrupt = store.temporary(0, "manual", "wav");
                try { store.copyToTree(corrupt, "content://invalid/tree/invalid", () -> false); fail("Revoked/invalid grant accepted"); } catch (IOException expected) {}
                corrupt.delete();
            }
        }
    }
    @Test public void repeatedEnableDisableScreenOffAndSave() throws Exception {
        try (ActivityScenario<SaidItActivity> scenario = ActivityScenario.launch(SaidItActivity.class)) {
            CountDownLatch connected = new CountDownLatch(1); SaidItService[] service = {null};
            ServiceConnection connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { service[0] = ((SaidItService.LocalBinder) binder).service(); connected.countDown(); }
                public void onServiceDisconnected(ComponentName name) {}
            };
            context.bindService(new Intent(context, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
            assertTrue(connected.await(10, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 3; i++) {
                    scenario.onActivity(a -> a.startForegroundService(new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
                    await(() -> service[0].availableSamples > 4800 && service[0].captureState.equals("Listening"));
                    service[0].stopListening(); await(() -> service[0].captureState.equals("Stopped")); assertFalse(service[0].isEnabled());
                }
                scenario.onActivity(a -> a.startForegroundService(new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
                await(() -> service[0].captureState.equals("Listening"));
                shell("input keyevent 223"); long before = service[0].endSample; Thread.sleep(1500);
                assertTrue("Screen-off audio did not advance", service[0].endSample > before);
                service[0].saveLookback(1, "manual"); await(() -> service[0].isSaving() || service[0].message.startsWith("Saved")); await(() -> !service[0].isSaving());
                assertTrue(service[0].message, service[0].message.startsWith("Saved"));
                shell("input keyevent 224");
                service[0].stopListening(); await(() -> service[0].captureState.equals("Stopped"));
            } finally { service[0].stopListening(); context.unbindService(connection); shell("input keyevent 224"); }
        }
    }
}
