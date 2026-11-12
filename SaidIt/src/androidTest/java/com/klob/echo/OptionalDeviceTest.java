package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.media.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
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
    context.getSharedPreferences(SaidItService.PREFS, 0).edit().clear().commit();
  }

  @Test
  public void gpxSurveyAndProtectedCredentials() throws Exception {
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
    String json =
        "{\"title\":\"Fixture\",\"start\":\"a\",\"nodes\":{\"a\":{\"type\":\"choice\",\"options\":[{\"label\":\"next\",\"next\":\"b\"}]},\"b\":{\"type\":\"end\"}}}";
    Survey survey = Survey.parse(json);
    assertEquals("b", survey.node("a").options.get(0).next);
    assertTrue(
        survey
            .toJson(Collections.singletonList(new Survey.Answer("a", "Question", "Answer")), 1000)
            .contains("Answer"));
    try {
      Survey.parse(json.replace("\"next\":\"b\"", "\"next\":\"missing\""));
      fail();
    } catch (org.json.JSONException expected) {
    }
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
    try (ActivityScenario<SaidItActivity> scenario =
        ActivityScenario.launch(SaidItActivity.class)) {
      CountDownLatch connected = new CountDownLatch(1);
      SaidItService[] s = {null};
      ServiceConnection c =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((SaidItService.LocalBinder) b).service();
              connected.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(new Intent(context, SaidItService.class), c, Context.BIND_AUTO_CREATE);
      assertTrue(connected.await(10, TimeUnit.SECONDS));
      try {
        scenario.onActivity(
            a ->
                a.startForegroundService(
                    new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
        await(() -> s[0].captureState.equals("Listening"));
        int before = new TraceStore(context).list().size();
        scenario.onActivity(a -> s[0].tools.camera(true));
        assertTrue(s[0].tools.camera.isRunning());
        long samples = s[0].endSample;
        scenario.onActivity(a -> s[0].tools.camera.captureNow());
        await(() -> new TraceStore(context).list().size() > before);
        assertTrue(s[0].endSample > samples);
        File video = null;
        for (File f : new TraceStore(context).list()) if (f.getName().endsWith(".mp4")) video = f;
        assertNotNull(video);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
          retriever.setDataSource(video.getPath());
          assertEquals(
              "yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
          assertNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
        } finally {
          retriever.release();
        }
      } finally {
        s[0].stopListening();
        await(() -> s[0].captureState.equals("Stopped"));
        context.unbindService(c);
      }
    }
  }

  @Test
  public void installedOfflineKeywordRecognizesFixture() throws Exception {
    assertKeyword(1);
  }

  @Test
  public void quietSpeechRecognizesWithoutAmplitudeGate() throws Exception {
    assertKeyword(50);
  }

  private void assertKeyword(int divisor) throws Exception {
    ModelInstaller.install(context);
    CountDownLatch heard = new CountDownLatch(1);
    KeywordDetector detector =
        new KeywordDetector(
            context,
            word -> {
              if (word.equals("one")) heard.countDown();
            });
    try {
      detector.start("one", 48000);
      await(
          () ->
              detector.getStatus() == KeywordDetector.Status.LISTENING
                  || detector.getStatus() == KeywordDetector.Status.ERROR);
      assertEquals(KeywordDetector.Status.LISTENING, detector.getStatus());
      long start = SystemClock.elapsedRealtime();
      try (InputStream in =
          InstrumentationRegistry.getInstrumentation()
              .getContext()
              .getAssets()
              .open("keyword-test.pcm")) {
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
          detector.feed(buffer, 0, count);
          Thread.sleep(20);
        }
      }
      assertTrue("Offline keyword not detected", heard.await(5, TimeUnit.SECONDS));
      android.util.Log.i(
          "EchoValidation",
          "Keyword fixture divisor="
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
    KeywordDetector detector = new KeywordDetector(absent, word -> {});
    detector.start("one", 48000);
    assertEquals(KeywordDetector.Status.NO_MODEL, detector.getStatus());
    assertFalse(detector.isRunning());
  }
}
