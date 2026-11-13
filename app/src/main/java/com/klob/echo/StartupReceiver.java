package com.klob.echo;

import android.content.*;
import com.klob.echo.recording.RecordingService;

/**
 * Boot never starts a microphone from the background. Time/rule alarms only wake an existing
 * service.
 */
public final class StartupReceiver extends android.content.BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (!context
        .getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
        .getBoolean("enabled", false)) return;
    if (RecordingService.ACTION_RULES.equals(intent.getAction())) {
      try {
        context.startService(
            new Intent(context, RecordingService.class).setAction(RecordingService.ACTION_RULES));
      } catch (RuntimeException e) {
        RecordingService.resumeNotification(context);
      }
    } else RecordingService.resumeNotification(context);
  }
}
