package com.klob.echo.recording;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.*;
import android.os.*;
import com.klob.echo.core.CameraCommand;
import com.klob.echo.storage.TraceStore;
import com.klob.echo.tools.CameraCommandDetector;
import com.klob.echo.tools.CameraRecorder;
import com.klob.echo.tools.GpxTrack;
import com.klob.echo.tools.ScreenCapturer;
import com.klob.echo.tools.TraceSync;
import java.io.*;
import java.util.concurrent.*;

/** Opt-in tools share service lifetime and captured PCM; none opens another microphone. */
public final class CaptureTools {
  private final RecordingService service;
  private final TraceStore traces;
  private final ExecutorService io =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
  public final CameraRecorder camera;
  public final ScreenCapturer screen;
  public final CameraCommandDetector commands;
  public final TraceSync sync;
  private long nextSync;
  private String activeWords = "";
  private final GpxTrack track = new GpxTrack();
  private final byte[] pcmBytes = new byte[4800];
  private LocationManager locations;
  public volatile boolean gps;
  private final Handler main = new Handler(Looper.getMainLooper());

  public CaptureTools(RecordingService service) {
    this.service = service;
    traces = new TraceStore(service);
    sync = new TraceSync(service, value -> service.message = value);
    camera =
        new CameraRecorder(
            service,
            new CameraRecorder.Listener() {
              public File tracesDir() {
                return traces.directory();
              }

              public void onCaptured(File file) {
                captured(file);
              }

              @Override
              public void onFailure(String reason) {
                service.message = reason;
              }
            });
    screen =
        new ScreenCapturer(
            service,
            new ScreenCapturer.Listener() {
              public File tracesDir() {
                return traces.directory();
              }

              public void onCaptured(File file) {
                captured(file);
              }

              @Override
              public void onFailure(String reason) {
                service.message = reason;
              }
            });
    commands =
        new CameraCommandDetector(
            service,
            facing -> {
              if (camera.isRunning() && !service.isPlaybackCapture()) camera.captureNow(facing);
            });
    service
        .getSystemService(android.app.NotificationManager.class)
        .deleteNotificationChannel("keyword");
  }

  private void submit(Runnable task) {
    try {
      io.execute(task);
    } catch (RejectedExecutionException e) {
      service.message = "Several items are being saved. Try again in a moment.";
    }
  }

  private void captured(File partial) {
    // Capture callbacks already run off the UI thread. Complete even if the service just stopped.
    try {
      if (partial.length() == 0) throw new IOException("Empty trace");
      traces.complete(partial);
      service.message = "Saved capture to Library.";
    } catch (IOException e) {
      partial.delete();
      service.message = "This capture could not be saved. Check free storage and try again.";
    }
  }

  public String summary() {
    return (gps ? " GPS" : "")
        + (camera.isCapturing()
            ? " // " + camera.status()
            : commands.isRunning() ? " // camera commands" : "")
        + (screen.isRunning() ? " screen" : "");
  }

  public boolean wanted(String tool) {
    return service
        .getSharedPreferences(RecordingService.PREFS, 0)
        .getBoolean(tool.equals("camera") ? "voice_camera_enabled" : tool + "_enabled", false);
  }

  public void setEnabled(String tool, boolean enabled, String unused) {
    if (!java.util.Arrays.asList("gps", "camera").contains(tool))
      throw new IllegalArgumentException("Unknown tool");
    service
        .getSharedPreferences(RecordingService.PREFS, 0)
        .edit()
        .putBoolean(tool.equals("camera") ? "voice_camera_enabled" : tool + "_enabled", enabled)
        .apply();
    if (!enabled) {
      if (tool.equals("gps")) gps(false);
      if (tool.equals("camera")) camera(false);
    } else if (service.isCapturing()) restoreConfigured();
    else service.message = "Preference saved. This tool will start when recording starts.";
  }

  public void restoreConfigured() {
    if (!service.isCapturing()) return;
    if (wanted("gps") && !gps) gps(true);
    if (wanted("camera")) camera(true);
  }

  public void camera(boolean enable) {
    if (!enable || service.isPlaybackCapture()) {
      commands.stop();
      camera.stop();
      service.refreshOptionalTypes();
      if (enable)
        service.message =
            "Camera commands need microphone recording. Switch audio source in Tools.";
      return;
    }
    android.content.SharedPreferences prefs =
        service.getSharedPreferences(RecordingService.PREFS, 0);
    camera.configure(prefs.getInt("camera_seconds", 30));
    if (!camera.isRunning() && service.promoteOptional(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA))
      camera.start();
    if (!camera.isRunning()) {
      service.message = "Camera permission or hardware is unavailable. Audio recording continues.";
      commands.stop();
      service.refreshOptionalTypes();
      return;
    }
    String back = prefs.getString("camera_back_phrase", CameraCommand.BACK);
    String front = prefs.getString("camera_front_phrase", CameraCommand.FRONT);
    String signature = back + "\n" + front;
    if (!commands.isRunning() || !signature.equals(activeWords)) {
      activeWords = signature;
      commands.start(back, front);
      service.message =
          commands.status() == CameraCommandDetector.Status.NO_MODEL
              ? "Download the English speech model to use camera commands."
              : "Camera commands are starting.";
    }
  }

  public boolean screen(int result, Intent consent) {
    if (service.isPlaybackCapture()) {
      service.message = "Switch back to microphone before enabling screenshots.";
      return false;
    }
    if (!service.promoteOptional(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION))
      return false;
    try {
      screen.setMinIntervalMillis(60000);
      boolean enabled = screen.start(result, consent);
      service.message =
          enabled
              ? "Screenshots on // one per minute while interactive."
              : "Screen capture unavailable.";
      return enabled;
    } catch (RuntimeException e) {
      screen.stop();
      service.message = "Screen capture could not start. Request consent again.";
      return false;
    }
  }

  public void stopScreen() {
    screen.stop();
    service.refreshOptionalTypes();
  }

  public void pcm(short[] samples, int count) {
    long now = SystemClock.elapsedRealtime();
    if (now >= nextSync
        && service.getSharedPreferences(RecordingService.PREFS, 0).getBoolean("sync", false)) {
      nextSync = now + 15 * 60 * 1000L;
      sync.sync();
    }
    camera.feed(samples, count, SystemClock.elapsedRealtimeNanos());
    if (!commands.isRunning()) return;
    for (int i = 0; i < count; i++) {
      pcmBytes[2 * i] = (byte) samples[i];
      pcmBytes[2 * i + 1] = (byte) (samples[i] >> 8);
    }
    commands.feed(pcmBytes, count * 2);
  }

  private final LocationListener locationListener =
      new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
          track.add(location);
        }

        @Override
        public void onProviderDisabled(String provider) {
          service.message =
              "Device location is off. Turn it on in Android settings to record your route.";
        }
      };
  private final Runnable flushTrack =
      new Runnable() {
        public void run() {
          writeTrack();
          if (gps) main.postDelayed(this, 60000);
        }
      };

  @SuppressLint("MissingPermission")
  public void gps(boolean enabled) {
    if (enabled && gps) return;
    if (!enabled) {
      gps = false;
      if (locations != null) locations.removeUpdates(locationListener);
      main.removeCallbacks(flushTrack);
      writeTrack();
      service.refreshOptionalTypes();
      return;
    }
    if (service.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
      service.message = "Location permission is required.";
      return;
    }
    if (!service.promoteOptional(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) return;
    locations = service.getSystemService(LocationManager.class);
    try {
      locations.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, 5000, 0, locationListener, Looper.getMainLooper());
      gps = true;
      main.postDelayed(flushTrack, 60000);
      service.message = "Location logging is active. Saved clips include matching GPS fixes.";
    } catch (RuntimeException e) {
      service.message = "GPS unavailable or permission denied.";
    }
  }

  private void writeTrack() {
    if (track.size() == 0) return;
    submit(() -> persistTrack());
  }

  /** The export worker waits for buffered fixes so a newly saved clip can display its route. */
  public boolean flushLocationForClip() {
    if (track.size() == 0) return true;
    try {
      return io.submit(this::persistTrack).get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
      return false;
    }
  }

  private boolean persistTrack() {
    if (track.size() == 0) return true;
    File partial = null;
    try {
      partial =
          TraceStore.uniqueFile(
              traces.directory(), TraceStore.timestampName(track.firstFixMillis()), ".gpx");
      track.writeTo(partial, "echo location trace", false);
      traces.complete(partial);
      track.acknowledgeWritten();
      service.message = "Location data saved.";
      return true;
    } catch (IOException e) {
      if (partial != null) partial.delete();
      service.message = "The route could not be saved. Check free storage and try again.";
      return false;
    }
  }

  public void stop() {
    camera.stop();
    screen.stop();
    commands.stop();
    gps(false);
  }

  public void close() {
    stop();
    camera.close();
    sync.close();
    io.shutdown();
  }
}
