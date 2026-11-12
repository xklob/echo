package com.klob.echo;
import android.content.*;
/** Boot never starts a microphone from the background. Time/rule alarms only wake an existing service. */
public final class BroadcastReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!context.getSharedPreferences(SaidItService.PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)) return;
        if (SaidItService.ACTION_RULES.equals(intent.getAction())) {
            try { context.startService(new Intent(context, SaidItService.class).setAction(SaidItService.ACTION_RULES)); }
            catch (RuntimeException e) { SaidItService.resumeNotification(context); }
        } else SaidItService.resumeNotification(context);
    }
}
