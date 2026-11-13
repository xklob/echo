package com.klob.echo.recording;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.klob.echo.R;
import com.klob.echo.storage.TraceStore;
import com.klob.echo.tools.GpxTrack;
import com.klob.echo.tools.KeywordDetector;
import com.klob.echo.tools.ScreenCapturer;
import com.klob.echo.tools.ShakeCameraCapturer;
import com.klob.echo.tools.TraceSync;
import java.io.*;
import java.util.concurrent.*;

/** Opt-in tools share service lifetime and captured PCM; none opens another microphone. */
public final class CaptureTools {
  private final RecordingService service;
  private final TraceStore traces;
  private final ExecutorService io =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
  public final ShakeCameraCapturer camera;
  public final ScreenCapturer screen;
  public final KeywordDetector keywords;
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
        new ShakeCameraCapturer(
            service,
            new ShakeCameraCapturer.Listener() {
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
    keywords =
        new KeywordDetector(
            service,
            word -> {
              service.message = "Keyword heard: " + word;
              try {
                service
                    .getSystemService(android.app.NotificationManager.class)
                    .notify(
                        460,
                        new NotificationCompat.Builder(service, "keyword")
                            .setSmallIcon(R.drawable.ic_stat_notify_recording)
                            .setContentTitle("Echo // keyword heard")
                            .setContentText(word)
                            .setAutoCancel(true)
                            .build());
              } catch (SecurityException ignored) {
              }
              submit(
                  () -> {
                    try {
                      traces.text(
                          "_keyword",
                          ".txt",
                          TraceStore.timestampName(System.currentTimeMillis()) + " // " + word);
                    } catch (IOException e) {
                      service.message = "Could not save keyword event.";
                    }
                  });
            });
    service
        .getSystemService(android.app.NotificationManager.class)
        .createNotificationChannel(
            new android.app.NotificationChannel(
                "keyword", "Keyword alerts", android.app.NotificationManager.IMPORTANCE_HIGH));
  }

  private void submit(Runnable task) {
    try {
      io.execute(task);
    } catch (RejectedExecutionException e) {
      service.message = "Several items are being saved. Try again in a moment.";
    }
  }

  private void captured(File partial) {
    submit(
        () -> {
          try {
            if (partial.length() == 0) throw new IOException("Empty trace");
            File file = traces.complete(partial);
            service.message = "Saved capture to Library.";
          } catch (IOException e) {
            partial.delete();
            service.message = "This capture could not be saved. Check free storage and try again.";
          }
        });
  }

  public String summary() {
    return (gps ? " GPS" : "")
        + (camera.isRunning() ? " camera" : "")
        + (screen.isRunning() ? " screen" : "")
        + (keywords.isRunning() ? " keywords" : "");
  }

  public boolean wanted(String tool) {
    return service
        .getSharedPreferences(RecordingService.PREFS, 0)
        .getBoolean(tool + "_enabled", false);
  }

  public void setEnabled(String tool, boolean enabled, String words) {
    if (!java.util.Arrays.asList("gps", "camera", "keywords").contains(tool))
      throw new IllegalArgumentException("Unknown tool");
    if (tool.equals("keywords") && enabled && !validKeywords(words))
      throw new IllegalArgumentException("Enter keywords using letters, spaces or commas.");
    android.content.SharedPreferences.Editor edit =
        service
            .getSharedPreferences(RecordingService.PREFS, 0)
            .edit()
            .putBoolean(tool + "_enabled", enabled);
    if (tool.equals("keywords")) edit.putString("keywords", words.trim());
    edit.apply();
    if (!enabled) {
      if (tool.equals("gps")) gps(false);
      if (tool.equals("camera")) camera(false);
      if (tool.equals("keywords")) keywords(false, words);
    } else if (service.isCapturing()) restoreConfigured();
    else service.message = "Preference saved. This tool will start when recording starts.";
  }

  public static boolean validKeywords(String words) {
    return words != null
        && !words.trim().isEmpty()
        && words.length() <= 200
        && words.matches("[\\p{L} ,;'-]+")
        && words.matches("(?s).*\\p{L}.*");
  }

  public void restoreConfigured() {
    if (!service.isCapturing()) return;
    if (wanted("gps") && !gps) gps(true);
    if (wanted("camera") && !camera.isRunning()) camera(true);
    if (wanted("keywords")) {
      String words =
          service.getSharedPreferences(RecordingService.PREFS, 0).getString("keywords", "");
      if (keywords.isRunning() && words.equals(activeWords)) return;
      if (keywords.isRunning()) keywords.stop();
      if (validKeywords(words)) keywords(true, words);
      else
        service.message = "Keyword alerts are enabled. Add words in Tools to start detecting them.";
    }
  }

  public void camera(boolean enable) {
    if (enable && camera.isRunning()) return;
    if (!enable) {
      camera.stop();
      service.refreshOptionalTypes();
      return;
    }
    if (service.promoteOptional(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)) {
      camera.configure(1, 15000, 15000, false, 5);
      camera.start();
      service.message =
          camera.isRunning()
              ? "Shake camera armed // four swings, then hold still. Rear camera only, silent"
                  + " 5-second clips."
              : "Camera or accelerometer unavailable.";
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

  public void keywords(boolean enabled, String words) {
    if (!enabled) {
      keywords.stop();
      return;
    }
    if (!service.isEnabled()) {
      service.message = "Start recording to begin detecting keywords.";
      return;
    }
    if (!validKeywords(words)) {
      service.message = "Enter keywords using letters, spaces or commas.";
      return;
    }
    activeWords = words;
    keywords.start(words, 48000);
    service.message =
        keywords.hasModel()
            ? "Keyword alerts are starting."
            : "Download the English speech model to detect keywords.";
  }

  public void pcm(short[] samples, int count) {
    long now = SystemClock.elapsedRealtime();
    if (now >= nextSync
        && service.getSharedPreferences(RecordingService.PREFS, 0).getBoolean("sync", false)) {
      nextSync = now + 15 * 60 * 1000L;
      sync.sync();
    }
    if (!keywords.isRunning()) return;
    for (int i = 0; i < count; i++) {
      pcmBytes[2 * i] = (byte) samples[i];
      pcmBytes[2 * i + 1] = (byte) (samples[i] >> 8);
    }
    keywords.feed(pcmBytes, 0, count * 2);
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
      service.message = "Location logging is active. Routes are saved to Library.";
    } catch (RuntimeException e) {
      service.message = "GPS unavailable or permission denied.";
    }
  }

  private void writeTrack() {
    if (track.size() == 0) return;
    submit(
        () -> {
          File partial = null;
          try {
            partial =
                TraceStore.uniqueFile(
                    traces.directory(), TraceStore.timestampName(track.firstFixMillis()), ".gpx");
            track.writeTo(partial, "Echo location trace", false);
            traces.complete(partial);
            track.acknowledgeWritten();
            service.message = "Saved location trace to Library.";
          } catch (IOException e) {
            if (partial != null) partial.delete();
            service.message = "The route could not be saved. Check free storage and try again.";
          }
        });
  }

  public void note(String text) {
    submit(
        () -> {
          try {
            File file = traces.text("_note", ".txt", text);
            service.message = "Saved note to Library.";
          } catch (IOException e) {
            service.message = "Could not save note.";
          }
        });
  }

  public void stop() {
    camera.stop();
    screen.stop();
    keywords.stop();
    gps(false);
  }

  public void close() {
    stop();
    sync.close();
    io.shutdown();
  }
}
