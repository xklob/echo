package com.klob.echo.settings;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;
import com.klob.echo.recording.RecordingService;

/** Stable preference keys preserve all existing installations. */
public final class AppSettings {
  private AppSettings() {}

  public static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE);
  }

  public static void save(Context context, String key, Object value, boolean nextRecording) {
    SharedPreferences p = prefs(context);
    SharedPreferences.Editor edit = p.edit();
    if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
    else if (value instanceof Integer) edit.putInt(key, (Integer) value);
    else edit.putString(key, value.toString());
    if (nextRecording && RecordingService.sessionActive)
      edit.putBoolean("history_settings_pending", true);
    edit.apply();
    if (key.equals("theme")) applyTheme(context);
    if ((key.startsWith("schedule") || key.equals("battery_min"))
        && RecordingService.sessionActive) {
      try {
        context.startService(
            new Intent(context, RecordingService.class).setAction(RecordingService.ACTION_RULES));
      } catch (RuntimeException ignored) {
        RecordingService.resumeNotification(context);
      }
    }
  }

  public static void applyTheme(Context context) {
    String choice = prefs(context).getString("theme", "system");
    int mode =
        choice.equals("dark")
            ? AppCompatDelegate.MODE_NIGHT_YES
            : choice.equals("light")
                ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    if (Build.VERSION.SDK_INT >= 31) {
      // MODE_NIGHT_AUTO is the per-application system-following mode.
      context
          .getSystemService(UiModeManager.class)
          .setApplicationNightMode(
              choice.equals("dark")
                  ? UiModeManager.MODE_NIGHT_YES
                  : choice.equals("light")
                      ? UiModeManager.MODE_NIGHT_NO
                      : UiModeManager.MODE_NIGHT_AUTO);
    }
    AppCompatDelegate.setDefaultNightMode(mode);
  }
}
