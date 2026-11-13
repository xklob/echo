package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.storage.ClipStore;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleDeviceTest {
  private Context context;

  private static void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
    assertTrue(check.getAsBoolean());
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    try (ParcelFileDescriptor grant =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(
                    "pm grant " + context.getPackageName() + " android.permission.RECORD_AUDIO");
        InputStream in = new FileInputStream(grant.getFileDescriptor())) {
      while (in.read() != -1) {}
    }
    context.getSharedPreferences(RecordingService.PREFS, 0).edit().clear().commit();
  }

  private interface Check {
    void run(ActivityScenario<MainActivity> activity, RecordingService service) throws Exception;
  }

  private void withService(Check check) throws Exception {
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      CountDownLatch latch = new CountDownLatch(1);
      RecordingService[] service = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              service[0] = ((RecordingService.LocalBinder) b).service();
              latch.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(
          new Intent(context, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
      try {
        check.run(activity, service[0]);
      } finally {
        service[0].stopListening();
        await(() -> service[0].captureState.equals("Stopped") && !service[0].isSaving());
        context.unbindService(connection);
      }
    }
  }

  @Test
  public void continuousTailAndManualStop() throws Exception {
    withService(
        (activity, service) -> {
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> service.captureState.equals("Listening") && service.availableSamples > 4800);
          service.setContinuous(true);
          await(() -> service.continuous);
          long before = service.endSample;
          await(() -> service.endSample > before + 12000);
          service.stopListening();
          await(() -> service.captureState.equals("Stopped") && !service.isSaving());
          assertTrue(
              new ClipStore(context)
                  .list().stream().anyMatch(f -> f.getParentFile().getName().equals("continuous")));
          assertFalse(service.isEnabled());
          assertFalse(RecordingService.sessionActive);
          long stopped = service.endSample;
          activity.onActivity(
              a ->
                  a.startService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_RULES)));
          Thread.sleep(300);
          assertEquals(stopped, service.endSample);
          assertFalse(service.isEnabled());
        });
  }

  @Test
  public void scheduledPauseResumeAndDeniedExactAlarmFallback() throws Exception {
    if (Build.VERSION.SDK_INT >= 31) {
      try (ParcelFileDescriptor deny =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand(
                      "appops set " + context.getPackageName() + " SCHEDULE_EXACT_ALARM deny");
          InputStream in = new FileInputStream(deny.getFileDescriptor())) {
        while (in.read() != -1) {}
      }
      assertFalse(
          context.getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms());
    }
    int now = java.time.LocalTime.now().getHour() * 60 + java.time.LocalTime.now().getMinute();
    context
        .getSharedPreferences(RecordingService.PREFS, 0)
        .edit()
        .putBoolean("schedule", true)
        .putInt("schedule_from", (now + 30) % 1440)
        .putInt("schedule_to", (now + 60) % 1440)
        .commit();
    withService(
        (activity, service) -> {
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> service.captureState.equals("Paused by schedule"));
          assertEquals(0, service.availableSamples);
          context
              .getSharedPreferences(RecordingService.PREFS, 0)
              .edit()
              .putInt("schedule_from", 0)
              .putInt("schedule_to", 0)
              .commit();
          activity.onActivity(
              a ->
                  a.startService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_RULES)));
          await(() -> service.captureState.equals("Listening") && service.availableSamples > 0);
        });
  }

  @Test
  public void autosaveRetainsManualClips() throws Exception {
    ClipStore clips = new ClipStore(context);
    File manual = clips.complete(clips.temporary(0, "manual", "wav"));
    context
        .getSharedPreferences(RecordingService.PREFS, 0)
        .edit()
        .putBoolean("autosave", true)
        .putInt("autosave_seconds", 10)
        .putInt("auto_keep", 1)
        .commit();
    final long started = System.currentTimeMillis();
    try {
      withService(
          (activity, service) -> {
            activity.onActivity(
                a ->
                    a.startForegroundService(
                        new Intent(a, RecordingService.class)
                            .setAction(RecordingService.ACTION_START)));
            await(() -> service.captureState.equals("Listening"));
            await(
                () ->
                    clips.list().stream()
                        .anyMatch(
                            f ->
                                f.getParentFile().getName().equals("auto")
                                    && f.lastModified() >= started
                                    && f.length() > 8));
            assertTrue(manual.exists());
            assertTrue(
                clips.list().stream()
                        .filter(f -> f.getParentFile().getName().equals("auto"))
                        .count()
                    <= 1);
          });
    } finally {
      manual.delete();
    }
  }

  @Test
  public void failedExternalCopyDoesNotRepeatCompletedAutosave() throws Exception {
    ClipStore clips = new ClipStore(context);
    context
        .getSharedPreferences(RecordingService.PREFS, 0)
        .edit()
        .putBoolean("autosave", true)
        .putInt("autosave_seconds", 10)
        .putInt("auto_keep", 10)
        .putString("tree", "content://com.klob.echo.test.documents/tree/revoked")
        .commit();
    long started = System.currentTimeMillis();
    withService(
        (activity, service) -> {
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(
              () ->
                  clips.list().stream()
                      .anyMatch(
                          f ->
                              f.getParentFile().getName().equals("auto")
                                  && f.lastModified() >= started
                                  && f.length() > 8));
          await(() -> !service.isSaving());
          long completed =
              clips.list().stream()
                  .filter(
                      f ->
                          f.getParentFile().getName().equals("auto") && f.lastModified() >= started)
                  .count();
          Thread.sleep(3000);
          assertEquals(
              completed,
              clips.list().stream()
                  .filter(
                      f ->
                          f.getParentFile().getName().equals("auto") && f.lastModified() >= started)
                  .count());
        });
  }
}
