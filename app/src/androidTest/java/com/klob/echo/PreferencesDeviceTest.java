package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.textfield.TextInputLayout;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.tools.CameraCommandDetector;
import com.klob.echo.ui.DetailActivity;
import com.klob.echo.ui.MainActivity;
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
        new String[] {"RECORD_AUDIO", "CAMERA", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"})
      try (ParcelFileDescriptor p =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand(
                      "pm grant " + context.getPackageName() + " android.permission." + name);
          InputStream in = new FileInputStream(p.getFileDescriptor())) {
        while (in.read() != -1) {}
      }
    context.getSharedPreferences(RecordingService.PREFS, 0).edit().clear().commit();
  }

  private interface Check {
    void run(ActivityScenario<MainActivity> activity, RecordingService service) throws Exception;
  }

  private void service(Check check) throws Exception {
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      CountDownLatch bound = new CountDownLatch(1);
      RecordingService[] s = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((RecordingService.LocalBinder) b).service();
              bound.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      context.bindService(
          new Intent(context, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
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

  private DetailActivity historySettings() {
    DetailActivity a =
        (DetailActivity)
            InstrumentationRegistry.getInstrumentation()
                .startActivitySync(
                    DetailActivity.intent(context, "history", R.string.history_quality)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    return a;
  }

  private AlertDialog memoryDialog(DetailActivity a) {
    ((View) text(a, a.getString(R.string.memory_budget)).getParent().getParent()).performClick();
    a.getSupportFragmentManager().executePendingTransactions();
    return (AlertDialog)
        ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("value")).requireDialog();
  }

  @Test
  public void savingHistorySettingsWhileRecordingSurvivesBackAndReopen() throws Exception {
    service(
        (activity, s) -> {
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> s.captureState.equals("Listening") && s.availableSamples > 4800);
          long started = s.startMs, before = s.endSample;
          int memory = Math.min(64, RecordingService.maxRamMb());
          DetailActivity edit = historySettings();
          InstrumentationRegistry.getInstrumentation()
              .runOnMainSync(
                  () -> {
                    AlertDialog dialog = memoryDialog(edit);
                    ((EditText) dialog.findViewById(R.id.input_value))
                        .setText(Integer.toString(memory));
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    ((CompoundButton) edit.findViewById(R.id.setting_disk)).setChecked(true);
                    edit.finish();
                  });
          InstrumentationRegistry.getInstrumentation().waitForIdleSync();
          SharedPreferences prefs = context.getSharedPreferences(RecordingService.PREFS, 0);
          assertEquals(memory, prefs.getInt("ram_mb", 0));
          assertTrue(prefs.getBoolean("disk", false));
          assertTrue(prefs.getBoolean("history_settings_pending", false));
          DetailActivity reopened = historySettings();
          InstrumentationRegistry.getInstrumentation()
              .runOnMainSync(
                  () -> {
                    AlertDialog dialog = memoryDialog(reopened);
                    assertEquals(
                        Integer.toString(memory),
                        ((EditText) dialog.findViewById(R.id.input_value)).getText().toString());
                    dialog.dismiss();
                    assertTrue(
                        ((CompoundButton) reopened.findViewById(R.id.setting_disk)).isChecked());
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
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> s.captureState.equals("Listening") && s.startMs > started);
          assertFalse(prefs.getBoolean("history_settings_pending", true));
        });
  }

  @Test
  public void invalidMemoryHasFieldErrorAndDoesNotSilentlyOverwriteSettings() {
    try (ActivityScenario<DetailActivity> scenario =
        ActivityScenario.launch(
            DetailActivity.intent(context, "history", R.string.history_quality))) {
      scenario.onActivity(
          a -> {
            AlertDialog dialog = memoryDialog(a);
            ((EditText) dialog.findViewById(R.id.input_value))
                .setText(Integer.toString(RecordingService.maxRamMb() + 1));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            assertNotNull(((TextInputLayout) dialog.findViewById(R.id.input_container)).getError());
          });
      assertEquals(
          16, context.getSharedPreferences(RecordingService.PREFS, 0).getInt("ram_mb", 16));
    }
  }

  @Test
  public void optionalToolChoicesSurviveStopAndServiceRecreation() throws Exception {
    service(
        (activity, s) -> {
          activity.onActivity(
              a -> {
                s.tools.setEnabled("gps", true, "");
                s.tools.setEnabled("camera", true, "");
              });
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("camera"));
          assertFalse(s.tools.gps);
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> s.tools.gps && s.tools.commands.status() != CameraCommandDetector.Status.OFF);
          s.stopListening();
          await(() -> !s.tools.gps && s.captureState.equals("Stopped"));
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("camera"));
        });
    Thread.sleep(300);
    service(
        (activity, s) -> {
          assertTrue(s.tools.wanted("gps"));
          assertTrue(s.tools.wanted("camera"));
          assertEquals(
              30,
              context.getSharedPreferences(RecordingService.PREFS, 0).getInt("camera_seconds", 30));
          activity.onActivity(
              a ->
                  a.startForegroundService(
                      new Intent(a, RecordingService.class)
                          .setAction(RecordingService.ACTION_START)));
          await(() -> s.tools.gps && s.tools.commands.status() != CameraCommandDetector.Status.OFF);
          activity.onActivity(a -> s.tools.setEnabled("gps", false, ""));
          assertFalse(s.tools.wanted("gps"));
          assertFalse(s.tools.gps);
        });
  }
}
