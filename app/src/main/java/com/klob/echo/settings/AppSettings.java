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
    if (key.equals("custom_lookback_seconds")) {
      edit.putInt("lookback_seconds", (Integer) value).putBoolean("lookback_custom", true);
    }
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

  public static int lookbackSeconds(Context context) {
    return Math.max(1, Math.min(86400, prefs(context).getInt("lookback_seconds", 60)));
  }

  public static int customLookbackSeconds(Context context) {
    int current = lookbackSeconds(context);
    int fallback = current != 60 && current != 300 && current != 900 ? current : 60;
    return Math.max(1, Math.min(86400, prefs(context).getInt("custom_lookback_seconds", fallback)));
  }

  public static boolean usesCustomLookback(Context context) {
    int n = lookbackSeconds(context);
    return prefs(context).getBoolean("lookback_custom", n != 60 && n != 300 && n != 900);
  }

  public static void selectLookback(Context context, int seconds, boolean custom) {
    int remembered = customLookbackSeconds(context);
    SharedPreferences.Editor edit =
        prefs(context)
            .edit()
            .putInt("lookback_seconds", Math.max(1, Math.min(86400, seconds)))
            .putBoolean("lookback_custom", custom);
    edit.putInt(
        "custom_lookback_seconds", custom ? Math.max(1, Math.min(86400, seconds)) : remembered);
    edit.apply();
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
