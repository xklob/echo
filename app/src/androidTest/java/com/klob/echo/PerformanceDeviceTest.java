package com.klob.echo;

import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.tools.CameraCommandDetector;
import com.klob.echo.tools.ModelInstaller;
import com.klob.echo.ui.MainActivity;
import java.io.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Opt-in bounded soak. Run with -e soak_seconds 360; logs contain generated test metrics only. */
@RunWith(AndroidJUnit4.class)
public class PerformanceDeviceTest {
  @Test
  public void screenOffWrapAndResourceSamples() throws Exception {
    int seconds =
        Integer.parseInt(InstrumentationRegistry.getArguments().getString("soak_seconds", "0"));
    Assume.assumeTrue("Opt-in soak only", seconds >= 30 && seconds <= 1800);
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    shell("pm grant " + c.getPackageName() + " android.permission.RECORD_AUDIO");
    c.getSharedPreferences(RecordingService.PREFS, 0)
        .edit()
        .clear()
        .putInt("ram_mb", 1)
        .putInt("bitrate", 96000)
        .putBoolean("autosave", true)
        .putInt("autosave_seconds", 30)
        .putInt("auto_keep", 5)
        .commit();
    try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(MainActivity.class)) {
      CountDownLatch connected = new CountDownLatch(1);
      RecordingService[] s = {null};
      ServiceConnection connection =
          new ServiceConnection() {
            public void onServiceConnected(ComponentName n, IBinder b) {
              s[0] = ((RecordingService.LocalBinder) b).service();
              connected.countDown();
            }

            public void onServiceDisconnected(ComponentName n) {}
          };
      c.bindService(new Intent(c, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
      assertTrue(connected.await(10, TimeUnit.SECONDS));
      try {
        activity.onActivity(
            a ->
                a.startForegroundService(
                    new Intent(a, RecordingService.class)
                        .setAction(RecordingService.ACTION_START)));
        for (int i = 0; i < 200 && !s[0].captureState.equals("Listening"); i++) Thread.sleep(50);
        assertEquals("Listening", s[0].captureState);
        shell("input keyevent SLEEP");
        long elapsed = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime();
        for (int i = 0; i <= seconds; i++) {
          if (i % 30 == 0 || i == seconds) log(s[0], "core", i, elapsed, cpu);
          if (i < seconds) Thread.sleep(1000);
        }
        assertTrue(s[0].endSample > seconds * 44000L);
        if (seconds >= 360)
          assertTrue(
              "Expected oldest RAM history to rotate",
              s[0].availableSamples < s[0].endSample - 480000);
        int commandSeconds =
            Integer.parseInt(
                InstrumentationRegistry.getArguments().getString("command_seconds", "0"));
        if (commandSeconds > 0 && commandSeconds <= 300) {
          ModelInstaller.install(c);
          s[0].tools.commands.start("echo back echo", "echo front echo");
          long commandStart = SystemClock.elapsedRealtime();
          cpu = android.os.Process.getElapsedCpuTime();
          for (int i = 0; i <= commandSeconds; i++) {
            if (i % 30 == 0 || i == commandSeconds)
              log(s[0], "camera commands", i, commandStart, cpu);
            if (i < commandSeconds) Thread.sleep(1000);
          }
          assertEquals(CameraCommandDetector.Status.LISTENING, s[0].tools.commands.status());
        }
      } finally {
        s[0].stopListening();
        for (int i = 0; i < 400 && s[0].isSaving(); i++) Thread.sleep(50);
        c.unbindService(connection);
        shell("input keyevent WAKEUP");
      }
    }
  }

  private static void log(RecordingService s, String phase, int second, long start, long cpu) {
    Debug.MemoryInfo m = new Debug.MemoryInfo();
    Debug.getMemoryInfo(m);
    android.util.Log.i(
        "EchoSoak",
        "phase="
            + phase
            + " second="
            + second
            + " elapsed_ms="
            + (SystemClock.elapsedRealtime() - start)
            + " cpu_ms="
            + (android.os.Process.getElapsedCpuTime() - cpu)
            + " pss_kib="
            + m.getTotalPss()
            + " java_used="
            + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            + " history_bytes="
            + s.memoryBytes
            + " disk_bytes="
            + s.diskBytes
            + " end_samples="
            + s.endSample
            + " available_samples="
            + s.availableSamples
            + " state="
            + s.captureState);
  }

  private static void shell(String command) throws Exception {
    try (ParcelFileDescriptor p =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        InputStream in = new FileInputStream(p.getFileDescriptor())) {
      while (in.read() != -1) {}
    }
  }
}
