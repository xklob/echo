package com.klob.echo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.media.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.util.concurrent.*;

/** Opt-in tools share service lifetime and captured PCM; none opens another microphone. */
final class OptionalTools {
  private final SaidItService service;
  private final TraceStore traces;
  private final ExecutorService io =
      new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
  final ShakeCameraCapturer camera;
  final ScreenCapturer screen;
  final KeywordDetector keywords;
  final TraceSync sync;
  private long nextSync;
  private final GpxTrack track = new GpxTrack();
  private final byte[] pcmBytes = new byte[4800];
  private LocationManager locations;
  volatile boolean gps;
  private final Handler main = new Handler(Looper.getMainLooper());

  OptionalTools(SaidItService service) {
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
              service.message = "Keyword // " + word;
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
      service.message = "Trace queue is full; try again.";
    }
  }

  private void captured(File partial) {
    submit(
        () -> {
          try {
            if (partial.length() == 0) throw new IOException("Empty trace");
            File file = traces.complete(partial);
            service.message = "Saved trace // " + file.getName();
          } catch (IOException e) {
            partial.delete();
            service.message = "Trace save failed.";
          }
        });
  }

  String summary() {
    return (gps ? " GPS" : "")
        + (camera.isRunning() ? " camera" : "")
        + (screen.isRunning() ? " screen" : "")
        + (keywords.isRunning() ? " keywords" : "");
  }

  boolean wanted(String tool) {
    return service
        .getSharedPreferences(SaidItService.PREFS, 0)
        .getBoolean(tool + "_enabled", false);
  }

  void setEnabled(String tool, boolean enabled, String words) {
    if (!java.util.Arrays.asList("gps", "camera", "keywords").contains(tool))
      throw new IllegalArgumentException("Unknown tool");
    if (tool.equals("keywords") && enabled && !validKeywords(words))
      throw new IllegalArgumentException("Enter keywords using letters, spaces or commas.");
    android.content.SharedPreferences.Editor edit =
        service
            .getSharedPreferences(SaidItService.PREFS, 0)
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

  static boolean validKeywords(String words) {
    return words != null
        && !words.trim().isEmpty()
        && words.length() <= 200
        && words.matches("[\\p{L} ,;'-]+");
  }

  void restoreConfigured() {
    if (!service.isCapturing()) return;
    if (wanted("gps") && !gps) gps(true);
    if (wanted("camera") && !camera.isRunning()) camera(true);
    if (wanted("keywords") && !keywords.isRunning()) {
      String words = service.getSharedPreferences(SaidItService.PREFS, 0).getString("keywords", "");
      if (validKeywords(words)) keywords(true, words);
      else service.message = "Keyword alerts are enabled. Add a keyword in settings.";
    }
  }

  void camera(boolean enable) {
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

  boolean screen(int result, Intent consent) {
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

  void stopScreen() {
    screen.stop();
    service.refreshOptionalTypes();
  }

  void keywords(boolean enabled, String words) {
    if (!enabled) {
      keywords.stop();
      return;
    }
    if (!service.isEnabled()) {
      service.message = "Start listening before enabling keyword alerts.";
      return;
    }
    if (!validKeywords(words)) {
      service.message = "Enter keywords using letters, spaces or commas.";
      return;
    }
    keywords.start(words, 48000);
    service.message = "Keyword detector // " + keywords.getStatus();
  }

  void pcm(short[] samples, int count) {
    long now = SystemClock.elapsedRealtime();
    if (now >= nextSync
        && service.getSharedPreferences(SaidItService.PREFS, 0).getBoolean("sync", false)) {
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
          service.message = "GPS provider disabled; no new fixes.";
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
  void gps(boolean enabled) {
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
      service.message = "GPS logging on // fixes are saved as timestamped GPX traces.";
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
            service.message = "Saved GPX trace.";
          } catch (IOException e) {
            if (partial != null) partial.delete();
            service.message = "Could not save GPX trace.";
          }
        });
  }

  void note(String text) {
    submit(
        () -> {
          try {
            File file = traces.text("_note", ".txt", text);
            service.message = "Saved note // " + file.getName();
          } catch (IOException e) {
            service.message = "Could not save note.";
          }
        });
  }

  void stop() {
    camera.stop();
    screen.stop();
    keywords.stop();
    gps(false);
  }

  void close() {
    stop();
    sync.close();
    io.shutdown();
  }
}
