package com.klob.echo;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import androidx.annotation.NonNull;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Collections;

/**
 * Optional silent video capture with relative-motion shake detection and per-camera cooldown.
 * Adapted from copa-sh/echo Java revisions 4a788a5 and 2a53be2; GPL-3.0.
 */
public class ShakeCameraCapturer implements SensorEventListener {

  private static final String TAG = ShakeCameraCapturer.class.getSimpleName();

  /**
   * How far from rest, in m/s², a swing has to reach to count as part of a shake, from the gentlest
   * gesture that still works to one nothing but deliberate shaking reaches. A phone being walked
   * with peaks at a few m/s²; shaking it on purpose passes 20 easily.
   */
  private static final float[] SHAKE_THRESHOLDS = {10f, 15f, 22f};

  /** Swings needed, so a single knock is not a shake. */
  private static final int SHAKE_PEAKS = 4;

  /** They all have to happen this close together. */
  private static final long SHAKE_WINDOW_MILLIS = 1500;

  /** A shake not followed by stillness this soon is forgotten. */
  private static final long ARMED_TIMEOUT_MILLIS = 10000;

  /** Movement below this, in m/s², counts as the phone being held still. */
  private static final float STILL_DEVIATION = 0.8f;

  /** How long it has to stay that still before the clip is recorded. */
  private static final long STILL_MILLIS = 1000;

  /**
   * How often the accelerometer is sampled. A shake is a handful of swings a second, so sampling it
   * as slowly as the UI rate would miss half of them.
   */
  private static final int SAMPLING_PERIOD_MICROS = 20000;

  /**
   * How long the sensor may hold samples back before delivering them. Fifty samples a second
   * delivered one at a time is fifty wakeups a second; letting the hardware batch them costs a
   * handful. Timing comes off each event's own timestamp, so a batch is read exactly as if it had
   * arrived live — what the batch does delay is the phone's answer to the gesture, and the tap that
   * says the phone was seen going still is no use a second after the fact, so this stays short
   * enough to keep the cues in the order they describe.
   */
  private static final int MAX_REPORT_LATENCY_MICROS = 200000;

  /** Clips are recorded at most this wide, so a video trace stays small. */
  private static final int MAX_VIDEO_WIDTH = 1280;

  private static final int FRAME_RATE = 24;

  /** A camera that neither records nor errors is abandoned this long after the clip should end. */
  private static final long CAMERA_TIMEOUT_MILLIS = 6000;

  /** Shortest clip an encoder can be asked for and still be expected to write a playable file. */
  private static final long MIN_CLIP_MILLIS = 1000;

  /**
   * The three things the phone says with its vibrator, as waveforms: milliseconds off, on, off,
   * on..., with an amplitude of the 255 a motor can be driven at for each. They mark the start and
   * the end of a recording, and are meant to be told apart through a pocket without looking, so
   * they differ in shape and not only in length: two taps for "recording now", one long buzz for
   * "saved", and two long buzzes for "nothing came of it", which is the one that has to be
   * unmistakable.
   */
  private static final long[] START_TIMINGS = {0, 35, 70, 35};

  private static final int[] START_AMPLITUDES = {0, 230, 0, 230};
  private static final long[] SAVED_TIMINGS = {0, 180};
  private static final int[] SAVED_AMPLITUDES = {0, 255};
  private static final long[] FAILED_TIMINGS = {0, 260, 160, 260};
  private static final int[] FAILED_AMPLITUDES = {0, 255, 0, 255};

  /** How long after a buzz ends the accelerometer is believed again. */
  private static final long BUZZ_SETTLE_MILLIS = 250;

  /** What the capturer needs from the service: where to write, and a nudge once a file lands. */
  public interface Listener {
    /** The directory traces are written to. Called off the main thread; may touch the disk. */
    File tracesDir();

    /** A clip has just been written. */
    void onCaptured(File file);

    default void onFailure(String reason) {}
  }

  private final Context context;
  private final Listener listener;

  private SensorManager sensorManager;
  private Sensor accelerometer;
  private HandlerThread sensorThread;
  private Handler sensorHandler;

  private CameraManager cameraManager;
  private HandlerThread cameraThread;
  private Handler cameraHandler;

  /** Resolved once capture starts; null on a phone with nothing to vibrate. */
  private volatile Vibrator vibrator;

  /**
   * elapsedRealtime the accelerometer is worth reading again from. The motor shakes the very sensor
   * the gesture is read off, so a cue of our own arrives as movement: without this, the buzzes that
   * mark a recording would be counted as swings of the next shake.
   */
  private volatile long ignoreSamplesUntil = 0;

  private volatile boolean running = false;

  /** How hard the phone has to be shaken, as an index into {@link #SHAKE_THRESHOLDS}. */
  private volatile int shakeLevel = 1;

  /** Shortest time between two clips of the same camera, kept apart back from front. */
  private volatile long backMinMillis = 8000;

  private volatile long frontMinMillis = 8000;

  /** The front camera is the one people want off, so it can be left out of every trigger. */
  private volatile boolean frontEnabled = true;

  /** How long each clip runs for. */
  private volatile long clipMillis = 5 * 1000L;

  /** elapsedRealtime of the last clip taken with each camera, so each is rate limited alone. */
  private volatile long lastBackElapsed = 0;

  private volatile long lastFrontElapsed = 0;

  private final com.klob.echo.core.ShakeGesture gesture = new com.klob.echo.core.ShakeGesture();

  /** Guards against a second capture starting while one is still in flight. */
  private volatile boolean capturing = false;

  private volatile Runnable activeFinish;

  public boolean isRunning() {
    return running;
  }

  public void captureNow() {
    if (running) maybeCapture();
  }

  public ShakeCameraCapturer(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  public boolean hasCameraPermission() {
    return context.checkSelfPermission(Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED;
  }

  /**
   * Everything the trigger is shaped by, applied live: the settings screen changes these while
   * capture is already running.
   */
  public void configure(
      int shakeLevel, long backMillis, long frontMillis, boolean frontEnabled, int clipSeconds) {
    this.shakeLevel = Math.max(0, Math.min(SHAKE_THRESHOLDS.length - 1, shakeLevel));
    this.backMinMillis = Math.max(0, backMillis);
    this.frontMinMillis = Math.max(0, frontMillis);
    this.frontEnabled = frontEnabled;
    this.clipMillis = Math.max(MIN_CLIP_MILLIS, clipSeconds * 1000L);
  }

  public synchronized void start() {
    if (running) return;
    if (!hasCameraPermission()) {
      Log.w(TAG, "No camera permission, shake capture stays off");
      return;
    }
    sensorManager = context.getSystemService(SensorManager.class);
    cameraManager = context.getSystemService(CameraManager.class);
    if (sensorManager == null || cameraManager == null) return;

    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    if (accelerometer == null) {
      Log.w(TAG, "No accelerometer, shake capture unavailable");
      return;
    }

    cameraThread = new HandlerThread("shakeCamera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());

    sensorThread = new HandlerThread("shakeSensor");
    sensorThread.start();
    sensorHandler = new Handler(sensorThread.getLooper());

    vibrator = resolveVibrator();
    ignoreSamplesUntil = 0;
    gesture.reset();
    running = true;
    sensorManager.registerListener(
        this, accelerometer, SAMPLING_PERIOD_MICROS, MAX_REPORT_LATENCY_MICROS, sensorHandler);
    Log.d(
        TAG,
        "Shake capture on, threshold "
            + SHAKE_THRESHOLDS[shakeLevel]
            + " m/s2, clips of "
            + clipMillis
            + " ms");
  }

  public synchronized void stop() {
    if (!running) return;
    running = false;
    if (sensorManager != null) sensorManager.unregisterListener(this);
    if (sensorThread != null) {
      sensorThread.quitSafely();
      sensorThread = null;
    }
    if (cameraHandler != null) {
      final Handler closing = cameraHandler;
      final HandlerThread thread = cameraThread;
      closing.post(
          () -> {
            if (activeFinish != null) activeFinish.run();
          });
      // Keep late camera-open callbacks alive long enough to close their device.
      closing.postDelayed(thread::quitSafely, CAMERA_TIMEOUT_MILLIS);
    }
    cameraThread = null;
    sensorHandler = null;
    cameraHandler = null;
    Log.d(TAG, "Shake capture off");
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!running || SystemClock.elapsedRealtime() < ignoreSamplesUntil) return;
    double x = event.values[0], y = event.values[1], z = event.values[2];
    float deviation =
        (float) Math.abs(Math.sqrt(x * x + y * y + z * z) - SensorManager.STANDARD_GRAVITY);
    if (gesture.accept(deviation, event.timestamp / 1000000L, shakeLevel)) maybeCapture();
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {}

  private void maybeCapture() {
    if (capturing) return;
    // A clip only fires for a camera whose own minimum has passed; that per-camera gap is the
    // whole rate limit, so if neither is due there is nothing to do.
    final long now = SystemClock.elapsedRealtime();
    if (!backDue(now) && !frontDue(now)) return;
    capturing = true;
    final Handler handler = cameraHandler;
    if (handler == null) {
      capturing = false;
      return;
    }
    handler.post(
        new Runnable() {
          @Override
          public void run() {
            captureDueCameras();
          }
        });
  }

  private boolean backDue(long now) {
    return now - lastBackElapsed >= backMinMillis;
  }

  private boolean frontDue(long now) {
    return frontEnabled && now - lastFrontElapsed >= frontMinMillis;
  }

  /** Camera thread: records whichever of the two cameras are due, one after another. */
  private void captureDueCameras() {
    final File dir = listener.tracesDir();
    final String base = TraceStore.timestampName(System.currentTimeMillis());
    final long now = SystemClock.elapsedRealtime();
    final boolean backDue = backDue(now);
    final boolean frontDue = frontDue(now);

    final ArrayDeque<String[]> queue = new ArrayDeque<String[]>();
    try {
      String back = null, front = null;
      float backAngle = -1f, frontAngle = -1f;
      for (String id : cameraManager.getCameraIdList()) {
        final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing == null) continue;
        if (!canRecord(characteristics)) continue;
        // Phones carry several lenses per side and hand them all to us as separate
        // cameras. Nobody is aiming this one: there is no preview and often no screen on,
        // so the phone is pointed with the hand and what matters is that whatever was
        // worth filming falls somewhere in frame. The widest lens is the one most likely
        // to have caught it.
        final float angle = diagonalFieldOfView(characteristics);
        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
          if (back == null || angle > backAngle) {
            back = id;
            backAngle = angle;
          }
        } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
          if (front == null || angle > frontAngle) {
            front = id;
            frontAngle = angle;
          }
        }
      }
      // The clock is stamped the moment a clip is committed to, not when it lands, so a
      // second gesture cannot slip another clip of one camera inside its minimum.
      if (back != null && backDue) {
        queue.add(new String[] {back, "back"});
        lastBackElapsed = now;
      }
      if (front != null && frontDue) {
        queue.add(new String[] {front, "front"});
        lastFrontElapsed = now;
      }
    } catch (Exception e) {
      Log.w(TAG, "Can't list cameras: " + e.getMessage());
    }

    if (queue.isEmpty()) {
      capturing = false;
      return;
    }
    // The gesture is answered as a whole, not once per camera.
    captureNext(queue, dir, base, new Round());
  }

  /** What one gesture is doing across however many cameras answer it. Camera thread only. */
  private static final class Round {
    boolean started = false;
    int written = 0;
  }

  private void captureNext(
      final ArrayDeque<String[]> queue, final File dir, final String base, final Round round) {
    if (!running) {
      capturing = false;
      return;
    }
    final String[] next = queue.poll();
    if (next == null) {
      // Every camera has had its turn, so now there is an answer worth giving: a clip on
      // disk, or nothing at all. Two cameras still say it once.
      if (round.written > 0) {
        buzz(SAVED_TIMINGS, SAVED_AMPLITUDES);
      } else {
        buzz(FAILED_TIMINGS, FAILED_AMPLITUDES);
        listener.onFailure("Camera capture failed; check permission and camera availability.");
      }
      capturing = false;
      return;
    }
    final Runnable onDone =
        new Runnable() {
          @Override
          public void run() {
            captureNext(queue, dir, base, round);
          }
        };
    try {
      captureOne(next[0], next[1], dir, base, round, onDone);
    } catch (Throwable t) {
      Log.w(TAG, "Clip from the " + next[1] + " camera failed: " + t.getMessage());
      onDone.run();
    }
  }

  @SuppressWarnings("MissingPermission")
  private void captureOne(
      final String cameraId,
      final String facing,
      final File dir,
      final String base,
      final Round round,
      final Runnable onDone)
      throws CameraAccessException {
    final CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
    final Size size = chooseSize(characteristics);
    final File file = TraceStore.uniqueFile(dir, base + "_" + facing, ".mp4");
    final long clip = clipMillis;
    // Held for as long as this clip lasts: stop() drops the field, and every callback below
    // runs on this thread and would fail on a null handler halfway through a recording.
    final Handler handler = cameraHandler;
    if (handler == null) {
      capturing = false;
      return;
    }

    final MediaRecorder recorder;
    try {
      recorder =
          buildRecorder(file, size, characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));
    } catch (Exception e) {
      Log.w(TAG, "Can't prepare the recorder for " + file.getName() + ": " + e.getMessage());
      file.delete();
      onDone.run();
      return;
    }
    final Surface surface = recorder.getSurface();

    // Everything below runs on the camera thread. A single latch of "done" makes sure the
    // camera, session and recorder are released exactly once, whether by the clip ending, an
    // error or the timeout below.
    final boolean[] finished = {false};
    final boolean[] recording = {false};
    final CameraDevice[] deviceHolder = {null};
    final CameraCaptureSession[] sessionHolder = {null};

    final Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            if (finished[0]) return;
            finished[0] = true;
            activeFinish = null;
            handler.removeCallbacks(this);
            boolean written = false;
            try {
              if (sessionHolder[0] != null) sessionHolder[0].stopRepeating();
            } catch (Exception ignore) {
            }
            try {
              // stop() throws when the encoder never got a frame, and the file it leaves
              // behind then has no moov atom and will not play.
              if (recording[0]) {
                recorder.stop();
                written = true;
              }
            } catch (Exception e) {
              Log.w(TAG, "Nothing recorded into " + file.getName() + ": " + e.getMessage());
            }
            try {
              recorder.reset();
              recorder.release();
            } catch (Exception ignore) {
            }
            try {
              surface.release();
            } catch (Exception ignore) {
            }
            try {
              if (sessionHolder[0] != null) sessionHolder[0].close();
            } catch (Exception ignore) {
            }
            try {
              if (deviceHolder[0] != null) deviceHolder[0].close();
            } catch (Exception ignore) {
            }

            if (written && file.length() > 0) {
              Log.d(TAG, "Saved " + file.getName());
              ++round.written;
              listener.onCaptured(file);
            } else {
              file.delete();
            }
            onDone.run();
          }
        };

    activeFinish = finish;

    // A camera that goes quiet must not wedge the queue forever.
    handler.postDelayed(finish, clip + CAMERA_TIMEOUT_MILLIS);

    cameraManager.openCamera(
        cameraId,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            deviceHolder[0] = device;
            if (finished[0] || !running) {
              device.close();
              finish.run();
              return;
            }
            try {
              device.createCaptureSession(
                  Collections.singletonList(surface),
                  new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                      sessionHolder[0] = session;
                      if (finished[0] || !running) {
                        session.close();
                        device.close();
                        finish.run();
                        return;
                      }
                      try {
                        final CaptureRequest.Builder request =
                            device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        request.addTarget(surface);
                        applyWidestFraming(request, characteristics);
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        session.setRepeatingRequest(request.build(), null, handler);
                        recorder.start();
                        recording[0] = true;
                        // Frames are being written. Said once for the gesture,
                        // whichever camera got there first.
                        if (!round.started) {
                          round.started = true;
                          buzz(START_TIMINGS, START_AMPLITUDES);
                        }
                        handler.postDelayed(finish, clip);
                      } catch (Exception e) {
                        Log.w(TAG, "Can't start recording: " + e.getMessage());
                        finish.run();
                      }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                      Log.w(TAG, "Capture session config failed for camera " + cameraId);
                      finish.run();
                    }
                  },
                  handler);
            } catch (Exception e) {
              Log.w(TAG, "Can't create session: " + e.getMessage());
              finish.run();
            }
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice device) {
            deviceHolder[0] = device;
            finish.run();
          }

          @Override
          public void onError(@NonNull CameraDevice device, int error) {
            deviceHolder[0] = device;
            Log.w(TAG, "Camera " + cameraId + " error " + error);
            finish.run();
          }
        },
        handler);
  }

  /**
   * Says one of the three things above through the vibrator. This is the whole of the feedback the
   * gesture gives: there is no preview, no shutter sound and, with the screen off, nothing to see,
   * so a shake that was understood and one that was not feel identical without it.
   *
   * <p>The amplitudes are asked for outright rather than borrowed from the system's own haptics,
   * which are tuned to be barely there — this has to be felt through a pocket, by someone who is
   * not looking at the phone and may not be holding it in a hand at all.
   *
   * <p>Camera thread. Vibrating is asynchronous, so nothing waits for it.
   */
  private void buzz(long[] timings, int[] amplitudes) {
    // Deafen the gesture for as long as the motor will be running, plus a moment for the
    // phone to stop ringing. Done first, and whether or not the vibration itself works out.
    long duration = 0;
    for (long segment : timings) duration += segment;
    ignoreSamplesUntil = SystemClock.elapsedRealtime() + duration + BUZZ_SETTLE_MILLIS;

    final Vibrator vibrator = this.vibrator;
    if (vibrator == null || !vibrator.hasVibrator()) return;
    try {
      // Without amplitude control every segment comes out at full strength, which keeps the
      // shapes distinguishable even where the strengths are not.
      vibrator.vibrate(
          vibrator.hasAmplitudeControl()
              ? VibrationEffect.createWaveform(timings, amplitudes, -1)
              : VibrationEffect.createWaveform(timings, -1));
    } catch (Exception e) {
      // A phone that will not vibrate is not a reason to lose the clip.
      Log.w(TAG, "Can't vibrate: " + e.getMessage());
    }
  }

  private Vibrator resolveVibrator() {
    try {
      if (android.os.Build.VERSION.SDK_INT >= 31) {
        final VibratorManager manager = context.getSystemService(VibratorManager.class);
        return manager == null ? null : manager.getDefaultVibrator();
      }
      return context.getSystemService(Vibrator.class);
    } catch (Exception e) {
      Log.w(TAG, "No vibrator: " + e.getMessage());
      return null;
    }
  }

  /** A recorder with no audio source at all, ready for the camera to draw into. */
  @SuppressWarnings("deprecation")
  private MediaRecorder buildRecorder(File file, Size size, Integer orientation) throws Exception {
    // The context-taking constructor only exists from API 31, and Echo still runs on 30.
    final MediaRecorder recorder =
        android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
    recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
    recorder.setOutputFile(file);
    recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
    recorder.setVideoSize(size.getWidth(), size.getHeight());
    recorder.setVideoFrameRate(FRAME_RATE);
    recorder.setVideoEncodingBitRate(bitRateFor(size));
    if (orientation != null) recorder.setOrientationHint(orientation);
    recorder.prepare();
    return recorder;
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
