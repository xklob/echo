package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.navigation.NavigationBarView;
import com.klob.echo.settings.AppSettings;
import com.klob.echo.storage.*;
import com.klob.echo.ui.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InterfaceDeviceTest {
  private Context context;

  private void shell(String command) throws Exception {
    try (ParcelFileDescriptor p =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        InputStream in = new FileInputStream(p.getFileDescriptor())) {
      while (in.read() != -1) {}
    }
  }

  private void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 10000;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(30);
    assertTrue(condition.getAsBoolean());
  }

  private static View find(View root, String label) {
    if (root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View found = find(((ViewGroup) root).getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  @Before
  public void setup() throws Exception {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSettings.prefs(context).edit().clear().commit();
    shell("cmd uimode night no");
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> AppSettings.applyTheme(context));
  }

  @After
  public void restore() throws Exception {
    AppSettings.prefs(context).edit().putString("theme", "system").commit();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> AppSettings.applyTheme(context));
    shell("cmd uimode night auto");
  }

  private void theme(ActivityScenario<MainActivity> activity, int choice) {
    activity.onActivity(
        a -> {
          a.findViewById(R.id.theme_choice).performClick();
          a.getSupportFragmentManager().executePendingTransactions();
          AlertDialog dialog =
              (AlertDialog)
                  ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("theme"))
                      .requireDialog();
          dialog.getListView().performItemClick(null, choice, choice);
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  @Test
  public void navigationAndThemeSurviveRecreationAndSystemChanges() throws Exception {
    assertEquals("system", AppSettings.prefs(context).getString("theme", "system"));
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      activity.onActivity(
          a ->
              ((NavigationBarView) a.findViewById(R.id.navigation))
                  .setSelectedItemId(R.id.nav_settings));
      activity.recreate();
      activity.onActivity(
          a ->
              assertEquals(
                  R.id.nav_settings,
                  ((NavigationBarView) a.findViewById(R.id.navigation)).getSelectedItemId()));
      theme(activity, 2);
      await(() -> AppSettings.prefs(context).getString("theme", "").equals("dark"));
      activity.recreate();
      activity.onActivity(
          a ->
              assertEquals(
                  Configuration.UI_MODE_NIGHT_YES,
                  a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
      theme(activity, 1);
      activity.onActivity(
          a ->
              assertEquals(
                  Configuration.UI_MODE_NIGHT_NO,
                  a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
      theme(activity, 0);
      shell("cmd uimode night yes");
      Thread.sleep(700);
      activity.onActivity(
          a ->
              assertEquals(
                  Configuration.UI_MODE_NIGHT_YES,
                  a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
      shell("cmd uimode night no");
      Thread.sleep(700);
      activity.onActivity(
          a ->
              assertEquals(
                  Configuration.UI_MODE_NIGHT_NO,
                  a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK));
    }
  }

  @Test
  public void noteDraftSurvivesRecreationAndSavesOneCopy() throws Exception {
    String value = "Rotation check " + System.nanoTime();
    int before = new TraceStore(context).list().size();
    try (ActivityScenario<DetailActivity> activity =
        ActivityScenario.launch(DetailActivity.intent(context, "tool_note", R.string.new_note))) {
      activity.onActivity(a -> ((EditText) a.findViewById(R.id.note_text)).setText(value));
      activity.recreate();
      activity.onActivity(
          a -> {
            assertEquals(value, ((EditText) a.findViewById(R.id.note_text)).getText().toString());
            find(a.getWindow().getDecorView(), "SAVE NOTE").performClick();
          });
      await(() -> new TraceStore(context).list().size() == before + 1);
      activity.recreate();
      Thread.sleep(150);
      assertEquals(before + 1, new TraceStore(context).list().size());
      boolean found = false;
      for (File f : new TraceStore(context).list())
        if (f.getName().endsWith(".txt") && Io.read(f.toPath()).equals(value)) found = true;
      assertTrue(found);
    }
  }

  @Test
  public void keywordChoiceIsGlobalAndNumericDraftSurvivesRecreation() throws Exception {
    try (ActivityScenario<DetailActivity> activity =
        ActivityScenario.launch(
            DetailActivity.intent(context, "tool_keywords", R.string.keywords))) {
      activity.onActivity(
          a -> {
            ((View) find(a.getWindow().getDecorView(), "Words to detect").getParent().getParent())
                .performClick();
            a.getSupportFragmentManager().executePendingTransactions();
            AlertDialog dialog =
                (AlertDialog)
                    ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("words"))
                        .requireDialog();
            ((EditText) dialog.findViewById(R.id.input_value)).setText("echo, remember");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ((CompoundButton) a.findViewById(R.id.keyword_enabled)).setChecked(true);
          });
      activity.recreate();
      activity.onActivity(
          a -> assertTrue(((CompoundButton) a.findViewById(R.id.keyword_enabled)).isChecked()));
    }
    assertTrue(AppSettings.prefs(context).getBoolean("keywords_enabled", false));
    try (ActivityScenario<DetailActivity> activity =
        ActivityScenario.launch(
            DetailActivity.intent(context, "history", R.string.history_quality))) {
      activity.onActivity(
          a -> {
            ((View) find(a.getWindow().getDecorView(), "Memory budget").getParent().getParent())
                .performClick();
            a.getSupportFragmentManager().executePendingTransactions();
            AlertDialog dialog =
                (AlertDialog)
                    ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("value"))
                        .requireDialog();
            ((EditText) dialog.findViewById(R.id.input_value)).setText("24");
          });
      activity.recreate();
      activity.onActivity(
          a -> {
            AlertDialog dialog =
                (AlertDialog)
                    ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("value"))
                        .requireDialog();
            assertEquals(
                "24", ((EditText) dialog.findViewById(R.id.input_value)).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
          });
      assertEquals(24, AppSettings.prefs(context).getInt("ram_mb", 0));
    }
  }
}
