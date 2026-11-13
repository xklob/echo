package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klob.echo.recording.*;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CaptureConflictDeviceTest {
  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(30);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  public void warnsBeforeTakingMicrophoneAndCancelPreservesOtherRecorder() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String pkg : new String[] {context.getPackageName(), "com.klob.echo.test"})
      try (ParcelFileDescriptor p =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand("pm grant " + pkg + " android.permission.RECORD_AUDIO");
          InputStream in = new FileInputStream(p.getFileDescriptor())) {
        while (in.read() != -1) {}
      }
    context.getSharedPreferences(RecordingService.PREFS, 0).edit().clear().commit();
    Intent fixture =
        new Intent()
            .setClassName("com.klob.echo.test", "com.klob.echo.MicrophoneFixtureActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    context.startActivity(new Intent(fixture).putExtra("duration_ms", 60000L));
    try {
      await(() -> CaptureConflicts.read(context, 0).microphone);
      try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
        activity.onActivity(
            a -> {
              a.findViewById(R.id.record_start_stop).performClick();
              a.getSupportFragmentManager().executePendingTransactions();
              AlertDialog dialog =
                  (AlertDialog)
                      ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("confirm"))
                          .requireDialog();
              assertTrue(dialog.isShowing());
              assertFalse(RecordingService.sessionActive);
              dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertFalse(
            context.getSharedPreferences(RecordingService.PREFS, 0).getBoolean("enabled", false));
        assertTrue(CaptureConflicts.read(context, 0).microphone);
        activity.onActivity(a -> a.findViewById(R.id.record_start_stop).performClick());
        activity.recreate();
        activity.onActivity(
            a -> {
              AlertDialog dialog =
                  (AlertDialog)
                      ((DialogFragment) a.getSupportFragmentManager().findFragmentByTag("confirm"))
                          .requireDialog();
              assertTrue(dialog.isShowing());
              assertFalse(RecordingService.sessionActive);
              dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
        await(() -> RecordingService.sessionActive);
      }
    } finally {
      context.sendBroadcast(
          new Intent("com.klob.echo.test.STOP_MIC_FIXTURE").setPackage("com.klob.echo.test"));
      context.startService(
          new Intent(context, RecordingService.class).setAction(RecordingService.ACTION_STOP));
      await(() -> !RecordingService.sessionActive);
    }
  }
}
