package com.klob.echo.tools;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.media.MediaRecorder;
import android.os.*;
import android.util.*;
import android.view.Surface;
import androidx.annotation.NonNull;
import com.klob.echo.storage.TraceStore;
import java.io.*;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 video with audio shared from Echo's microphone. Camera framing adapted from copa-sh/echo,
 * GPL-3.0.
 */
public final class CameraRecorder {
  private static final String TAG = "EchoCamera";
  private static final int MAX_VIDEO_WIDTH = 1280, FRAME_RATE = 24;

  public interface Listener {
    File tracesDir();

    void onCaptured(File file);

    void onFailure(String reason);
  }

  private final Context context;
  private final Listener listener;
  private final CameraManager manager;
  private final HandlerThread thread = new HandlerThread("cameraRecorder");
  private final Handler handler;
  private final ExecutorService finishWorker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean();
  private volatile boolean running, closed;
  private volatile int seconds = 30;
  private volatile CameraAudio audio;
  private volatile Runnable activeFinish;
  private volatile String status = "";

  public CameraRecorder(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
    manager = context.getSystemService(CameraManager.class);
    thread.start();
    handler = new Handler(thread.getLooper());
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isCapturing() {
    return busy.get();
  }

  public String status() {
    return status;
  }

  /**
   * Enumerating cameras is safe even on tablets with no usable camera service or broken hardware.
   */
  public static boolean[] cameras(Context context) {
    boolean[] sides = new boolean[2];
    CameraManager manager = context.getSystemService(CameraManager.class);
    if (manager == null) return sides;
    try {
      for (String id : manager.getCameraIdList()) {
        CameraCharacteristics info = manager.getCameraCharacteristics(id);
        Integer facing = info.get(CameraCharacteristics.LENS_FACING);
        if (facing == null || !canRecord(info)) continue;
        if (facing == CameraCharacteristics.LENS_FACING_BACK) sides[0] = true;
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) sides[1] = true;
      }
    } catch (Exception | LinkageError ignored) {
      // Audio recording has no dependency on a working camera implementation.
    }
    return sides;
  }

  public boolean hasCamera(String facing) {
    return cameras(context)[facing.equals("front") ? 1 : 0];
  }

  public void configure(int seconds) {
    this.seconds = Math.max(5, Math.min(600, seconds));
  }

  public void start() {
    boolean[] sides = cameras(context);
    running =
        !closed
            && manager != null
            && (sides[0] || sides[1])
            && context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    if (!sides[0] && !sides[1])
      status = "No video camera is available. Audio recording still works.";
  }

  public void stop() {
    running = false;
    handler.post(
        () -> {
          if (activeFinish != null) activeFinish.run();
        });
  }

  public void close() {
    closed = true;
    stop();
    handler.postDelayed(
        () -> {
          thread.quitSafely();
          finishWorker.shutdown();
        },
        8000);
  }

  public void feed(short[] pcm, int count, long endNanos) {
    CameraAudio current = audio;
    if (current != null) current.feed(pcm, count, endNanos);
  }

  public void captureNow(String facing) {
    if (!(facing.equals("back") || facing.equals("front"))) return;
    if (busy.get()) return;
    if (!hasCamera(facing)) {
      listener.onFailure("The " + facing + " camera is unavailable. Audio recording continues.");
      status = "The " + facing + " camera is unavailable. Audio recording continues.";
      return;
    }
    if (!running) {
      listener.onFailure("Camera access is unavailable. Audio recording still works.");
      return;
    }
    if (!busy.compareAndSet(false, true)) return;
    status = "Starting " + facing + " camera";
    handler.post(
        () -> {
          try {
            record(facing);
          } catch (Exception e) {
            Log.w(TAG, "Camera failed to open", e);
            fail(
                "Camera could not start. It may be disabled, busy or not working. Audio recording"
                    + " continues.");
          }
        });
  }

  private void fail(String reason) {
    busy.set(false);
    status = reason;
    listener.onFailure(reason);
  }

  @SuppressWarnings({"MissingPermission", "deprecation"})
  private void record(String facing) throws Exception {
    if (!running) {
      busy.set(false);
      return;
    }
    int lens =
        facing.equals("front")
            ? CameraCharacteristics.LENS_FACING_FRONT
            : CameraCharacteristics.LENS_FACING_BACK;
    String id = null;
    float angle = -1;
    for (String candidate : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
      Integer f = c.get(CameraCharacteristics.LENS_FACING);
      if (f != null && f == lens && canRecord(c)) {
        float a = diagonalFieldOfView(c);
        if (id == null || a > angle) {
          id = candidate;
          angle = a;
        }
      }
    }
    if (id == null) {
      fail("The " + facing + " camera is unavailable. Audio recording continues.");
      return;
    }
    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
    Size size = chooseSize(characteristics);
    Integer rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    int orientation = rotation == null ? 0 : rotation;
    File directory = listener.tracesDir();
    File video = File.createTempFile("camera-video-", ".part", context.getCacheDir());
    File sound = File.createTempFile("camera-audio-", ".part", context.getCacheDir());
    int duration = seconds;
    MediaRecorder recorder =
        Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
    Surface surface;
    try {
      recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setOutputFile(video);
      recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
      recorder.setVideoSize(size.getWidth(), size.getHeight());
      recorder.setVideoFrameRate(FRAME_RATE);
      recorder.setVideoEncodingBitRate(bitRateFor(size));
      recorder.setOrientationHint(orientation);
      recorder.prepare();
      surface = recorder.getSurface();
    } catch (Exception e) {
      recorder.release();
      video.delete();
      sound.delete();
      throw e;
    }
    boolean[] finished = {false}, started = {false};
    String[] failureReason = {""};
    CameraDevice[] device = {null};
    CameraCaptureSession[] session = {null};
    CameraAudio[] capturedAudio = {null};
    long[] wallStart = {0};
    Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            if (finished[0]) return;
            finished[0] = true;
            activeFinish = null;
            handler.removeCallbacks(this);
            audio = null;
            if (capturedAudio[0] != null) capturedAudio[0].stopInput();
            boolean valid = false;
            try {
              if (session[0] != null) session[0].stopRepeating();
            } catch (Exception ignored) {
            }
            try {
              if (started[0]) {
                recorder.stop();
                valid = true;
              }
            } catch (Exception ignored) {
            }
            try {
              recorder.release();
            } catch (Exception ignored) {
            }
            surface.release();
            if (session[0] != null) session[0].close();
            if (device[0] != null) device[0].close();
            final boolean recorded = valid;
            status = "Saving camera recording";
            finishWorker.execute(
                () -> {
                  File complete = null;
                  try {
                    if (capturedAudio[0] != null) capturedAudio[0].finish();
                    if (!recorded || capturedAudio[0] == null)
                      throw new IOException("No video or microphone audio");
                    complete =
                        TraceStore.uniqueFile(
                            directory,
                            TraceStore.timestampName(wallStart[0]) + "_" + facing,
                            ".mp4");
                    VideoMuxer.combine(video, sound, complete, orientation);
                    listener.onCaptured(complete);
                  } catch (Exception e) {
                    if (complete != null) complete.delete();
                    if (failureReason[0].isEmpty())
                      failureReason[0] =
                          "Camera recording failed. The camera may be disabled or not working."
                              + " Audio recording continues.";
                    Log.w(TAG, "Camera recording could not be finalized", e);
                    listener.onFailure(failureReason[0]);
                  } finally {
                    video.delete();
                    sound.delete();
                    busy.set(false);
                    status = failureReason[0];
                  }
                });
          }
        };
    activeFinish = finish;
    // A broken camera must fail promptly instead of waiting through the requested clip duration.
    handler.postDelayed(finish, 8000);
    try {
      manager.openCamera(
          id,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
              device[0] = camera;
              if (finished[0] || !running) {
                camera.close();
                finish.run();
                return;
              }
              try {
                camera.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                      @Override
                      public void onConfigured(@NonNull CameraCaptureSession capture) {
                        session[0] = capture;
                        if (finished[0] || !running) {
                          capture.close();
                          camera.close();
                          finish.run();
                          return;
                        }
                        try {
                          CaptureRequest.Builder request =
                              camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                          request.addTarget(surface);
                          applyWidestFraming(request, characteristics);
                          request.set(
                              CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                          capture.setRepeatingRequest(request.build(), null, handler);
                          recorder.start();
                          started[0] = true;
                          wallStart[0] = System.currentTimeMillis();
                          capturedAudio[0] =
                              new CameraAudio(sound, SystemClock.elapsedRealtimeNanos(), duration);
                          audio = capturedAudio[0];
                          status = "Recording " + facing + " camera";
                          handler.removeCallbacks(finish);
                          handler.postDelayed(finish, duration * 1000L);
                        } catch (Exception e) {
                          failureReason[0] =
                              "Camera could not record video. Audio recording continues.";
                          finish.run();
                        }
                      }

                      @Override
                      public void onConfigureFailed(@NonNull CameraCaptureSession capture) {
                        failureReason[0] =
                            "This camera cannot record video. Audio recording continues.";
                        capture.close();
                        finish.run();
                      }
                    },
                    handler);
              } catch (Exception e) {
                finish.run();
              }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
              failureReason[0] = "Camera disconnected. Audio recording continues.";
              camera.close();
              finish.run();
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
              failureReason[0] =
                  error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                      ? "Camera is in use. Try again after the other app closes it. Audio recording"
                          + " continues."
                      : error == ERROR_CAMERA_DISABLED
                          ? "Camera access is disabled in Android. Audio recording continues."
                          : "Camera hardware is unavailable or not working. Audio recording"
                              + " continues.";
              camera.close();
              finish.run();
            }
          },
          handler);
    } catch (Exception e) {
      finish.run();
    }
  }

  /** Enough bits for a legible clip of this size and no more. */
  private static int bitRateFor(Size size) {
    final long pixels = (long) size.getWidth() * size.getHeight();
    return (int) Math.max(1000000L, Math.min(8000000L, pixels * FRAME_RATE / 12));
  }

  private static Size chooseSize(CameraCharacteristics characteristics) {
    try {
      final Size[] sizes =
          characteristics
              .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
              .getOutputSizes(MediaRecorder.class);
      if (sizes != null && sizes.length > 0) {
        final float sensorAspect = sensorAspectRatio(characteristics);
        Size best = widestUpTo(sizes, sensorAspect);
        // A camera offering nothing in the sensor's own shape is served by whatever it
        // does offer rather than by nothing at all.
        if (best == null) best = widestUpTo(sizes, 0f);
        if (best != null) return best;
        Size smallest = sizes[0];
        for (Size s : sizes) if (s.getWidth() < smallest.getWidth()) smallest = s;
        return smallest;
      }
    } catch (Exception ignore) {
    }
    return new Size(640, 480);
  }

  /** How far from an aspect ratio a size may sit and still count as that shape. */
  private static final float ASPECT_TOLERANCE = 0.04f;

  /**
   * The widest of these sizes within {@link #MAX_VIDEO_WIDTH}, of the given shape, or of any shape
   * when {@code aspect} is zero. Null when nothing qualifies.
   */
  private static Size widestUpTo(Size[] sizes, float aspect) {
    Size best = null;
    for (Size s : sizes) {
      if (s.getWidth() > MAX_VIDEO_WIDTH) continue;
      if (aspect > 0f) {
        final float ratio = (float) s.getWidth() / s.getHeight();
        if (Math.abs(ratio - aspect) > ASPECT_TOLERANCE * aspect) continue;
      }
      if (best == null || s.getWidth() > best.getWidth()) best = s;
    }
    return best;
  }

  /** The shape of the whole sensor, long side over short, or 0 when the camera will not say. */
  private static float sensorAspectRatio(CameraCharacteristics characteristics) {
    try {
      final SizeF physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
      if (physical != null && physical.getWidth() > 0 && physical.getHeight() > 0) {
        return Math.max(physical.getWidth(), physical.getHeight())
            / Math.min(physical.getWidth(), physical.getHeight());
      }
      final Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      if (active != null && active.width() > 0 && active.height() > 0) {
        return (float) Math.max(active.width(), active.height())
            / Math.min(active.width(), active.height());
      }
    } catch (Exception ignore) {
    }
    return 0f;
  }

  /**
   * How much of the world this camera takes in, as the angle across the diagonal of its sensor in
   * degrees, or 0 when it will not say enough to work that out. The shortest focal length it offers
   * is the one used: on a lens that zooms, the wide end is what it is capable of.
   */
  private static float diagonalFieldOfView(CameraCharacteristics characteristics) {
    try {
      final SizeF physical = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
      final float[] focalLengths =
          characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
      if (physical == null || focalLengths == null || focalLengths.length == 0) return 0f;
      float shortest = Float.MAX_VALUE;
      for (float f : focalLengths) if (f > 0f && f < shortest) shortest = f;
      if (shortest == Float.MAX_VALUE) return 0f;
      final double diagonal =
          Math.sqrt(
              physical.getWidth() * physical.getWidth()
                  + physical.getHeight() * physical.getHeight());
      return (float) Math.toDegrees(2 * Math.atan(diagonal / (2 * shortest)));
    } catch (Exception ignore) {
    }
    return 0f;
  }

  /**
   * Whether this camera can be asked for video at all. Phones list depth, infrared and other helper
   * cameras alongside the ones people think of as cameras, and those either refuse a recording
   * session or fill it with something nobody can watch.
   */
  private static boolean canRecord(CameraCharacteristics characteristics) {
    try {
      final int[] capabilities =
          characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
      if (capabilities == null) return false;
      boolean compatible = false;
      for (int c : capabilities) {
        if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
          compatible = true;
          break;
        }
      }
      if (!compatible) return false;
      final Size[] sizes =
          characteristics
              .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
              .getOutputSizes(MediaRecorder.class);
      return sizes != null && sizes.length > 0;
    } catch (Exception ignore) {
      return false;
    }
  }

  /**
   * Asks for the widest framing the chosen camera has left to give, on top of it having been the
   * widest camera there was.
   *
   * <p>Zooming out is the part that matters. Several phones — Pixels above all — do not hand out
   * their ultra wide lens as a camera of its own: the back camera is one logical camera that covers
   * all of its lenses, and the wide one is reached only by asking for a zoom ratio below one, which
   * is what {@code CONTROL_ZOOM_RATIO_RANGE} says is possible. Left alone the camera starts at 1.0,
   * which is the main lens, so the widest lens on the phone would go unused exactly where it cannot
   * be selected by id.
   */
  private static void applyWidestFraming(
      CaptureRequest.Builder request, CameraCharacteristics characteristics) {
    try {
      final Range<Float> zoomRange =
          characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
      if (zoomRange != null && zoomRange.getLower() != null) {
        request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRange.getLower());
        return;
      }
      // Older framing, for a camera that does not do zoom ratios: the crop is the zoom, and
      // the whole active array is no crop at all. Set only as a fallback, since the two
      // ways of saying it are not meant to be mixed.
      final Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
      if (active != null) request.set(CaptureRequest.SCALER_CROP_REGION, active);
    } catch (Exception e) {
      // A camera that will not be framed still records; it just records less of the room.
      Log.w(TAG, "Can't widen the framing: " + e.getMessage());
    }
  }
}
