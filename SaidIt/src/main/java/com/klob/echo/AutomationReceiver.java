package com.klob.echo;

import android.content.*;

/** Explicitly opt-in v1 contract. No caller receives private paths or file URIs. */
public final class AutomationReceiver extends android.content.BroadcastReceiver {
  static final String PREFIX = BuildConfig.APPLICATION_ID + ".automation.v1.";

  @Override
  public void onReceive(Context context, Intent intent) {
    android.content.SharedPreferences prefs =
        context.getSharedPreferences(SaidItService.PREFS, Context.MODE_PRIVATE);
    if (!prefs.getBoolean("automation", false)) return;
    String action = intent.getAction();
    if (action == null || !action.startsWith(PREFIX)) return;
    try {
      switch (action.substring(PREFIX.length())) {
        case "START":
          context.startForegroundService(
              new Intent(context, SaidItService.class).setAction(SaidItService.ACTION_START));
          break;
        case "STOP":
          context.startService(
              new Intent(context, SaidItService.class).setAction(SaidItService.ACTION_STOP));
          break;
        case "SAVE":
          {
            long seconds = intent.getLongExtra("seconds", 60);
            if (seconds < 1 || seconds > 86400) return;
            context.startService(
                new Intent(context, SaidItService.class)
                    .setAction(SaidItService.ACTION_SAVE)
                    .putExtra("seconds", seconds));
            break;
          }
        case "CONTINUOUS_START":
        case "CONTINUOUS_STOP":
          context.startService(
              new Intent(context, SaidItService.class)
                  .setAction(SaidItService.ACTION_CONTINUOUS)
                  .putExtra("enabled", action.endsWith("START")));
          break;
        case "SET_LIMITS":
          {
            if (prefs.getBoolean("enabled", false)) return;
            int ram = intent.getIntExtra("ram_mb", prefs.getInt("ram_mb", 16)),
                disk = intent.getIntExtra("disk_mb", prefs.getInt("disk_mb", 128));
            if (ram < 1 || ram > SaidItService.maxRamMb() || disk < 16 || disk > 1024) return;
            prefs.edit().putInt("ram_mb", ram).putInt("disk_mb", disk).apply();
            break;
          }
        default:
          break;
      }
    } catch (RuntimeException e) {
      SaidItService.resumeNotification(context);
    }
  }
}
