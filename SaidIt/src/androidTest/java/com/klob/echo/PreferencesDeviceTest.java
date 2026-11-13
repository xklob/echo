package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PreferencesDeviceTest {
  private Context context;

  private void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(30);
    assertTrue(check.getAsBoolean());
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String name :
        new String[] {"RECORD_AUDIO", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"})
      try (ParcelFileDescriptor p =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand(
                      "pm grant " + context.getPackageName() + " android.permission." + name);
          InputStream in = new FileInputStream(p.getFileDescriptor())) {
        while (in.read() != -1) {}
      }
    context.getSharedPreferences(SaidItService.PREFS, 0).edit().clear().commit();
  }

  private interface Check {
    void run(ActivityScenario<SaidItActivity> activity, SaidItService service) throws Exception;
  }

  private void service(Check check) throws Exception {
    try (ActivityScenario<SaidItActivity> activity =
        ActivityScenario.launch(SaidItActivity.class)) {
      CountDownLatch bound = new CountDownLatch(1);
      SaidItService[] s = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((SaidItService.LocalBinder) b).service();
              bound.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(
          new Intent(context, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
      assertTrue(bound.await(10, TimeUnit.SECONDS));
      try {
        check.run(activity, s[0]);
      } finally {
        s[0].stopListening();
        await(() -> s[0].captureState.equals("Stopped") && !s[0].isSaving());
        context.unbindService(connection);
      }
    }
  }

  private static View find(View root, java.util.function.Predicate<View> test) {
    if (test.test(root)) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View child = find(((ViewGroup) root).getChildAt(i), test);
        if (child != null) return child;
      }
    return null;
  }

  private static View text(android.app.Activity a, String text) {
    View v =
        find(
            a.getWindow().getDecorView(),
            w -> w instanceof TextView && ((TextView) w).getText().toString().equals(text));
    assertNotNull(text, v);
    return v;
  }

  private static EditText field(android.app.Activity a, String description) {
    View v =
        find(
            a.getWindow().getDecorView(),
            w ->
                w instanceof EditText
                    && description.contentEquals(
                        w.getContentDescription() == null ? "" : w.getContentDescription()));
    assertNotNull(description, v);
    return (EditText) v;
  }

  @Test
  public void savingHistorySettingsWhileRecordingSurvivesBackAndReopen() throws Exception {
    service(
        (activity, s) -> {
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
          await(() -> s.captureState.equals("Listening") && s.availableSamples > 4800);
          long started = s.startMs, before = s.endSample;
          int memory = Math.min(64, SaidItService.maxRamMb());
          String label = "RAM history budget in MB // max " + SaidItService.maxRamMb();
          SettingsActivity edit =
              (SettingsActivity)
                  InstrumentationRegistry.getInstrumentation()
                      .startActivitySync(
                          new Intent(context, SettingsActivity.class)
                              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          InstrumentationRegistry.getInstrumentation()
              .runOnMainSync(
                  () -> {
                    field(edit, label).setText(Integer.toString(memory));
                    ((CompoundButton) text(edit, "Use disk history")).setChecked(true);
                    text(edit, "APPLY SETTINGS").performClick();
                    edit.finish();
                  });
          InstrumentationRegistry.getInstrumentation().waitForIdleSync();
          SharedPreferences prefs = context.getSharedPreferences(SaidItService.PREFS, 0);
          assertEquals(memory, prefs.getInt("ram_mb", 0));
          assertTrue(prefs.getBoolean("disk", false));
          assertTrue(prefs.getBoolean("history_settings_pending", false));
          SettingsActivity reopened =
              (SettingsActivity)
                  InstrumentationRegistry.getInstrumentation()
                      .startActivitySync(
                          new Intent(context, SettingsActivity.class)
                              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
          InstrumentationRegistry.getInstrumentation()
              .runOnMainSync(
                  () -> {
                    assertEquals(
                        Integer.toString(memory), field(reopened, label).getText().toString());
                    assertTrue(((CompoundButton) text(reopened, "Use disk history")).isChecked());
                    reopened.finish();
                  });
          InstrumentationRegistry.getInstrumentation().waitForIdleSync();
          await(() -> s.endSample > before + 4800);
          assertEquals(started, s.startMs);
          s.stopListening();
          await(() -> s.captureState.equals("Stopped"));
          Thread.sleep(200);
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
          await(() -> s.captureState.equals("Listening") && s.startMs > started);
          assertFalse(prefs.getBoolean("history_settings_pending", true));
        });
  }

  @Test
  public void invalidMemoryHasFieldErrorAndDoesNotSilentlyOverwriteSettings() {
    try (ActivityScenario<SettingsActivity> settings =
        ActivityScenario.launch(SettingsActivity.class)) {
      settings.onActivity(
          a -> {
            EditText ram = field(a, "RAM history budget in MB // max " + SaidItService.maxRamMb());
            ram.setText(Integer.toString(SaidItService.maxRamMb() + 1));
            text(a, "APPLY SETTINGS").performClick();
            assertNotNull(ram.getError());
          });
      assertEquals(16, context.getSharedPreferences(SaidItService.PREFS, 0).getInt("ram_mb", 16));
    }
  }

  @Test
  public void optionalToolChoicesSurviveStopAndServiceRecreation() throws Exception {
    service(
        (activity, s) -> {
          activity.onActivity(
              a -> {
                s.tools.setEnabled("gps", true, "");
                s.tools.setEnabled("keywords", true, "one");
              });
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("keywords"));
          assertFalse(s.tools.gps);
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
          await(() -> s.tools.gps && s.tools.keywords.getStatus() != KeywordDetector.Status.OFF);
          s.stopListening();
          await(() -> !s.tools.gps && s.captureState.equals("Stopped"));
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("keywords"));
        });
    Thread.sleep(300);
    service(
        (activity, s) -> {
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("keywords"));
          assertEquals(
              "one",
              context.getSharedPreferences(SaidItService.PREFS, 0).getString("keywords", ""));
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, SaidItService.class).setAction(SaidItService.ACTION_START)));
          await(() -> s.tools.gps && s.tools.keywords.getStatus() != KeywordDetector.Status.OFF);
          activity.onActivity(a -> s.tools.setEnabled("gps", false, ""));
          assertFalse(s.tools.wanted("gps"));
          assertFalse(s.tools.gps);
        });
  }
}
