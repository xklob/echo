package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InterruptionDeviceTest {
  private static void await(java.util.function.BooleanSupplier check) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (!check.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
    assertTrue(check.getAsBoolean());
  }

  @Test
  public void competingPrivateMicrophoneFreezesHistoryAndRecovers() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    for (String pkg : new String[] {"com.klob.echo", "com.klob.echo.test"}) {
      try (ParcelFileDescriptor p =
              InstrumentationRegistry.getInstrumentation()
                  .getUiAutomation()
                  .executeShellCommand("pm grant " + pkg + " android.permission.RECORD_AUDIO");
          InputStream in = new FileInputStream(p.getFileDescriptor())) {
        while (in.read() != -1) {}
      }
    }
    c.getSharedPreferences(SaidItService.PREFS, 0).edit().clear().commit();
    try (ActivityScenario<SaidItActivity> a = ActivityScenario.launch(SaidItActivity.class)) {
      CountDownLatch connected = new CountDownLatch(1);
      SaidItService[] s = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((SaidItService.LocalBinder) b).service();
              connected.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      c.bindService(new Intent(c, SaidItService.class), connection, Context.BIND_AUTO_CREATE);
      assertTrue(connected.await(10, TimeUnit.SECONDS));
      try {
        a.onActivity(
            activity ->
                activity.startForegroundService(
                    new Intent(activity, SaidItService.class)
                        .setAction(SaidItService.ACTION_START)));
        await(() -> s[0].captureState.equals("Listening") && s[0].availableSamples > 4800);
        long oldStart = s[0].startMs;
        c.startActivity(
            new Intent()
                .setClassName("com.klob.echo.test", "com.klob.echo.MicrophoneFixtureActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> s[0].captureState.equals("Microphone unavailable"));
        long frozen = s[0].endSample;
        Thread.sleep(1500);
        assertEquals("Silenced samples became usable history", frozen, s[0].endSample);
        await(
            () ->
                s[0].captureState.equals("Listening")
                    && s[0].startMs > oldStart
                    && s[0].availableSamples > 4800);
        assertTrue("Recovery should report the capture gap", s[0].message.contains("gap"));
      } finally {
        s[0].stopListening();
        await(() -> s[0].captureState.equals("Stopped"));
        c.unbindService(connection);
      }
    }
  }
}
