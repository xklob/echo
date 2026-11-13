package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.media.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.storage.Io;
import com.klob.echo.storage.TraceStore;
import com.klob.echo.tools.CameraCommandDetector;
import com.klob.echo.tools.GpxTrack;
import com.klob.echo.tools.ModelInstaller;
import com.klob.echo.tools.SyncCredentials;
import com.klob.echo.tools.TraceSync;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OptionalDeviceTest {
  private Context context;

  private static void shell(String command) throws Exception {
    try (ParcelFileDescriptor p =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        InputStream in = new FileInputStream(p.getFileDescriptor())) {
      while (in.read() != -1) {}
    }
  }

  private static void await(java.util.function.BooleanSupplier test) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!test.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
    assertTrue(test.getAsBoolean());
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String permission :
        new String[] {
          "RECORD_AUDIO",
          "POST_NOTIFICATIONS",
          "CAMERA",
          "ACCESS_FINE_LOCATION",
          "ACCESS_COARSE_LOCATION"
        }) shell("pm grant " + context.getPackageName() + " android.permission." + permission);
    context.getSharedPreferences(RecordingService.PREFS, 0).edit().clear().commit();
  }

  @Test
  public void gpxAndProtectedCredentials() throws Exception {
    GpxTrack track = new GpxTrack();
    track.add(37.5, -77.6, 10, 1000);
    track.add(37.6, -77.7, Double.NaN, 70000);
    File file = new File(context.getCacheDir(), "test.gpx");
    assertEquals(2, track.writeTo(file, "a < b & c"));
    String xml = Io.read(file.toPath());
    assertTrue(xml.contains("a &lt; b &amp; c"));
    assertEquals(3, xml.split("<trkseg>").length);
    assertEquals(0, track.size());
    track.add(1, 2, 3, 1000);
    try {
      track.writeTo(context.getCacheDir(), "write failure");
      fail();
    } catch (IOException expected) {
    }
    assertEquals(1, track.size());
    String generatedFixture = "test-only-not-a-real-token-00000000000000000000";
    SyncCredentials.set(context, generatedFixture);
    assertEquals(generatedFixture, SyncCredentials.get(context));
    assertFalse(
        context
            .getSharedPreferences("sync_secret", 0)
            .getString("encrypted", "")
            .contains(generatedFixture));
    context.getSharedPreferences("sync_secret", 0).edit().clear().commit();
    assertEquals("https://example.com", TraceSync.endpoint("https://example.com/"));
    try {
      TraceSync.endpoint("https://example.com/?token=test");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void cameraAndMicrophoneConcurrentClip() throws Exception {
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      CountDownLatch connected = new CountDownLatch(1);
      RecordingService[] s = {null};
      ServiceConnection c =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((RecordingService.LocalBinder) b).service();
              connected.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(new Intent(context, RecordingService.class), c, Context.BIND_AUTO_CREATE);
      assertTrue(connected.await(10, TimeUnit.SECONDS));
      try {
        scenario.onActivity(
            a ->
                a.startForegroundService(
                    new Intent(a, RecordingService.class)
                        .setAction(RecordingService.ACTION_START)));
        await(() -> s[0].captureState.equals("Listening"));
        scenario.onActivity(a -> s[0].tools.camera(true));
        assertTrue(s[0].tools.camera.isRunning());
        s[0].tools.camera.configure(5);
        for (String facing : new String[] {"back", "front"}) {
          Set<File> before = new HashSet<>(new TraceStore(context).list());
          long samples = s[0].endSample;
          if (!s[0].tools.camera.hasCamera(facing)) {
            scenario.onActivity(a -> s[0].tools.camera.captureNow(facing));
            await(() -> s[0].tools.camera.status().contains("unavailable"));
            Thread.sleep(300);
            assertTrue(s[0].isCapturing());
            assertTrue(s[0].endSample > samples);
            assertEquals(before, new HashSet<>(new TraceStore(context).list()));
            continue;
          }
          scenario.onActivity(a -> s[0].tools.camera.captureNow(facing));
          await(() -> new TraceStore(context).list().stream().anyMatch(f -> !before.contains(f)));
          assertTrue(s[0].endSample > samples);
          File video =
              new TraceStore(context)
                  .list().stream().filter(f -> !before.contains(f)).findFirst().get();
          assertTrue(video.getName().contains("_" + facing));
          MediaMetadataRetriever retriever = new MediaMetadataRetriever();
          try {
            retriever.setDataSource(video.getPath());
            assertEquals(
                "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            assertEquals(
                "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            long duration =
                Long.parseLong(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            assertTrue(duration >= 4500 && duration <= 6500);
          } finally {
            retriever.release();
          }
          await(() -> !s[0].tools.camera.isCapturing());
        }
      } finally {
        s[0].stopListening();
        await(() -> s[0].captureState.equals("Stopped"));
        context.unbindService(c);
      }
    }
  }

  @Test
  public void installedOfflineCameraCommandsRecognizeBothDirections() throws Exception {
    assertCommand("back", 1);
    assertCommand("front", 1);
  }

  @Test
  public void quietSpeechRecognizesWithoutAmplitudeGate() throws Exception {
    assertCommand("back", 50);
  }

  private void assertCommand(String face, int divisor) throws Exception {
    ModelInstaller.install(context);
    CountDownLatch heard = new CountDownLatch(1);
    CameraCommandDetector detector =
        new CameraCommandDetector(
            context,
            word -> {
              if (word.equals(face)) heard.countDown();
            });
    try {
      detector.start("echo back echo", "echo front echo");
      await(
          () ->
              detector.status() == CameraCommandDetector.Status.LISTENING
                  || detector.status() == CameraCommandDetector.Status.ERROR);
      assertEquals(CameraCommandDetector.Status.LISTENING, detector.status());
      long start = SystemClock.elapsedRealtime();
      try (InputStream in =
          InstrumentationRegistry.getInstrumentation()
              .getContext()
              .getAssets()
              .open("camera-" + face + ".pcm")) {
        byte[] buffer = new byte[1920];
        int count;
        while ((count = in.read(buffer)) != -1 && heard.getCount() > 0) {
          if (divisor != 1)
            for (int i = 0; i + 1 < count; i += 2) {
              short value = (short) ((buffer[i] & 255) | buffer[i + 1] << 8);
              value = (short) (value / divisor);
              buffer[i] = (byte) value;
              buffer[i + 1] = (byte) (value >> 8);
            }
          detector.feed(buffer, count);
          Thread.sleep(20);
        }
      }
      assertTrue("Offline camera command not detected", heard.await(5, TimeUnit.SECONDS));
      android.util.Log.i(
          "EchoValidation",
          "Camera command fixture divisor="
              + divisor
              + " elapsed_ms="
              + (SystemClock.elapsedRealtime() - start));
    } finally {
      detector.stop();
    }
  }

  @Test
  public void missingModelIsAnExplicitState() {
    Context absent =
        new android.content.ContextWrapper(
            InstrumentationRegistry.getInstrumentation().getTargetContext()) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return new File(super.getFilesDir(), "missing-model-fixture");
          }
        };
    CameraCommandDetector detector = new CameraCommandDetector(absent, word -> {});
    detector.start("echo back echo", "echo front echo");
    assertEquals(CameraCommandDetector.Status.NO_MODEL, detector.status());
    assertFalse(detector.isRunning());
  }

  @Test
  public void absentCameraServiceIsOptional() throws Exception {
    Context absent =
        new ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public Object getSystemService(String name) {
            return Context.CAMERA_SERVICE.equals(name) ? null : super.getSystemService(name);
          }
        };
    java.util.concurrent.atomic.AtomicReference<String> failure =
        new java.util.concurrent.atomic.AtomicReference<>("");
    com.klob.echo.tools.CameraRecorder recorder =
        new com.klob.echo.tools.CameraRecorder(
            absent,
            new com.klob.echo.tools.CameraRecorder.Listener() {
              public File tracesDir() {
                return new TraceStore(context).directory();
              }

              public void onCaptured(File file) {
                fail("No camera must never produce a video");
              }

              public void onFailure(String reason) {
                failure.set(reason);
              }
            });
    try {
      recorder.start();
      assertFalse(recorder.isRunning());
      assertFalse(recorder.hasCamera("back"));
      assertFalse(recorder.hasCamera("front"));
      recorder.captureNow("back");
      assertTrue(failure.get().contains("unavailable"));
      assertFalse(recorder.isCapturing());
    } finally {
      recorder.close();
    }
  }
}
