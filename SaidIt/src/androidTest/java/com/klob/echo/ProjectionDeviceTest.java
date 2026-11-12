package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.media.projection.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ProjectionDeviceTest {
  private static void await(java.util.function.BooleanSupplier test) throws Exception {
    long until = SystemClock.elapsedRealtime() + 20000;
    while (!test.getAsBoolean() && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    assertTrue("Timed out", test.getAsBoolean());
  }

  private static void approve(UiDevice device) throws Exception {
    UiObject2 button =
        device.wait(
            Until.findObject(
                By.text(
                    Pattern.compile(
                        "(?i)(start now|start recording|share screen|start sharing|share|start)"))),
            8000);
    if (button == null) button = device.findObject(By.res("android", "button1"));
    if (button == null) {
      device.dumpWindowHierarchy(
          new File(
              InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),
              "consent-ui.xml"));
      fail("Native capture consent button not found");
    }
    button.click();
  }

  private static Intent consent(ToolsActivity activity) {
    MediaProjectionManager manager = activity.getSystemService(MediaProjectionManager.class);
    return Build.VERSION.SDK_INT >= 34
        ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        : manager.createScreenCaptureIntent();
  }

  @Test
  public void consentedScreenshotAndOtherAppPlaybackCapture() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (ParcelFileDescriptor grant =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(
                    "pm grant " + context.getPackageName() + " android.permission.RECORD_AUDIO");
        InputStream input = new FileInputStream(grant.getFileDescriptor())) {
      while (input.read() != -1) {}
    }
    UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    Configurator.getInstance().setWaitForIdleTimeout(0);
    context
        .getSharedPreferences(SaidItService.PREFS, 0)
        .edit()
        .clear()
        .putString("format", "wav")
        .commit();
    try (ActivityScenario<SaidItActivity> main = ActivityScenario.launch(SaidItActivity.class)) {
      CountDownLatch connected = new CountDownLatch(1);
      SaidItService[] service = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              service[0] = ((SaidItService.LocalBinder) b).service();
              connected.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(
          new Intent(context, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
      assertTrue(connected.await(10, TimeUnit.SECONDS));
      try {
        main.onActivity(
            a ->
                a.startForegroundService(
                    new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
        await(() -> service[0].captureState.equals("Listening"));
        try (ActivityScenario<ToolsActivity> tools = ActivityScenario.launch(ToolsActivity.class)) {
          Thread.sleep(300);
          int before = new TraceStore(context).list().size();
          tools.onActivity(a -> a.startActivityForResult(consent(a), 33));
          approve(device);
          await(() -> service[0].tools.screen.isRunning());
          Thread.sleep(500);
          tools.onActivity(a -> service[0].tools.screen.captureNow());
          await(() -> new TraceStore(context).list().size() > before);
          tools.onActivity(a -> service[0].tools.stopScreen());
          tools.onActivity(a -> a.startActivityForResult(consent(a), 35));
          approve(device);
          await(
              () ->
                  service[0].isPlaybackCapture()
                      && service[0].captureState.contains("device audio"));
          context.startActivity(
              new Intent()
                  .setClassName("com.klob.echo.test", "com.klob.echo.PlaybackFixtureActivity")
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          Thread.sleep(2000);
          service[0].saveLookback(1, "manual");
          await(() -> service[0].isSaving() || service[0].message.startsWith("Saved"));
          await(() -> !service[0].isSaving());
          assertTrue(service[0].message, service[0].message.startsWith("Saved"));
          File captured = new ClipStore(context).list().get(0);
          assertTrue(captured.getName().endsWith(".wav"));
          try (RandomAccessFile wav = new RandomAccessFile(captured, "r")) {
            wav.seek(44);
            long square = 0;
            int samples = 0;
            while (wav.getFilePointer() + 1 < wav.length()) {
              short sample = (short) (wav.readUnsignedByte() | wav.readByte() << 8);
              square += (long) sample * sample;
              samples++;
            }
            assertTrue("Playback capture was silent", Math.sqrt(square / (double) samples) > 100);
          }
          assertTrue(
              Io.read(new File(captured.getPath() + ".json").toPath()).contains("device_audio"));
          device.pressBack();
        }
      } finally {
        service[0].stopListening();
        await(() -> service[0].captureState.equals("Stopped"));
        context.unbindService(connection);
        Configurator.getInstance().setWaitForIdleTimeout(10000);
      }
    }
  }
}
