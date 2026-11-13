package com.klob.echo.core;

/**
 * Relative-motion gesture adapted from copa-sh/echo 2a53be2b1744d9e79e3f46d7b5c158d61a5a951e,
 * GPL-3.0.
 */
public final class ShakeGesture {
  private static final float[] SHAKE_THRESHOLDS = {10f, 15f, 22f};

  /** Swings needed, so a single knock is not a shake. */
  private static final int SHAKE_PEAKS = 4;

  /** They all have to happen this close together. */
  private static final long SHAKE_WINDOW_MILLIS = 1500;

  /** A shake not followed by settling this soon is forgotten. */
  private static final long ARMED_TIMEOUT_MILLIS = 10000;

  /**
   * How far above its old background, in m/s², the movement of the last moment may sit and still
   * count as the phone having settled back into what it was doing. It is a tolerance and not a
   * speed limit: on a phone that was lying on a table the background is near zero and this is the
   * old absolute threshold for stillness, while on one being walked with it is satisfied by walking
   * on at the same pace.
   */
  private static final float STILL_TOLERANCE = 0.8f;

  /**
   * How much of the background is added to that tolerance. A phone whose background is a walk does
   * not sit at that background: each step is a swing of its own and the recent average still
   * ripples with them however it is smoothed, so a tolerance tight enough for a phone on a table
   * would be crossed twice a second by someone simply walking, and the gesture would never finish
   * for them. The rougher the ride, the rougher the measurement of it, so what is allowed grows
   * with the background it is measured against.
   */
  private static final float STILL_TOLERANCE_PER_BACKGROUND = 0.6f;

  /** How long it has to stay settled before the clip is recorded. */
  private static final long STILL_MILLIS = 1000;

  /**
   * The time constants of the two running averages the gesture is read off, in milliseconds.
   *
   * <p>The background is slow on purpose: it has to carry the pace of a walk across the whole of a
   * gesture without being budged by any one swing, and it is what a shake is measured against.
   *
   * <p>The recent one stands for "how the phone is moving right now", and its length is a straight
   * trade. A single sample will not do, since walking crosses zero acceleration twice a step and
   * would read as stillness at those instants; the longer this is, the more of the ripple of a walk
   * it smooths away, and the longer the phone takes to be seen settling once the shaking stops. A
   * few tenths of a second flattens a walking pace to a fraction of what {@link
   * #STILL_TOLERANCE_PER_BACKGROUND} allows while keeping the wait after a shake short enough that
   * the gesture still feels like an answer.
   */
  private static final float BACKGROUND_TAU_MILLIS = 4000f;

  private static final float RECENT_TAU_MILLIS = 350f;

  /**
   * A gap in the samples longer than this — the sensor stopped, the phone slept, a buzz was being
   * ignored for a long time — leaves an average describing a moment that has passed, so both are
   * started over from the sample that ends the gap.
   */
  private static final long SAMPLE_GAP_RESET_MILLIS = 2000;

  /**
   * How long the background is allowed to catch up before any of this is believed. Capture can be
   * switched on, and the sensor can come back from a gap, in the middle of a walk, and one sample
   * is a poor guess at what the phone is living with: taken at the wrong instant of a step it reads
   * as a phone at rest, and then every step of the walk looks like a shake. So for a moment the
   * background simply is the recent movement, arriving at the truth in a fraction of a second
   * rather than in the several the slow average would take, and nothing is read as a gesture until
   * it has.
   */
  private static final long WARMUP_MILLIS = 2000;

  /**
   * The longest the background may stay frozen for a gesture in progress.
   *
   * <p>Freezing it is what keeps a shake out of the average it is measured against, but it is only
   * safe for as long as there really is a gesture. Movement that sits above the threshold and stays
   * there — a phone in the hand of someone running, a phone on a machine — is read as one shake
   * after another, and each re-arms the trigger before the last has timed out, so the freeze would
   * never lift on its own and the background would stay frozen at whatever it held before the
   * movement began: too low ever to be exceeded again, leaving the trigger armed and the phone deaf
   * to the gesture from then on. Past this the whole thing is abandoned and the background is
   * caught up to what the phone is actually doing.
   */
  private static final long BACKGROUND_FREEZE_LIMIT_MILLIS = 15000;

  private final long[] peakAt = new long[SHAKE_PEAKS];
  private int peakIndex = 0;
  private int peaksSeen = 0;

  /** When the last swing was counted, so a lone swing does not hold the background frozen. */
  private long lastPeakAt = 0;

  /** True while movement is still above the threshold of the swing being counted. */
  private boolean inPeak = false;

  /** elapsedRealtime of the shake now waiting for the movement to settle, or 0 when none is. */
  private long armedAt = 0;

  /** elapsedRealtime the phone settled at while armed, or 0 while it is still shaken up. */
  private long stillSince = 0;

  // The two running averages, sensor thread only.
  /** The movement the phone was already living with, m/s². Frozen while a gesture is under way. */
  private float background = 0f;

  /** The movement of the last fraction of a second, m/s². */
  private float recent = 0f;

  /** False until the first sample seeds both of them. */
  private boolean levelsSeeded = false;

  /** When they were seeded, so the background is given a moment to find its level. */
  private long seededAt = 0;

  /** When the background stopped following the phone, or 0 while it is still following it. */
  private long frozenSince = 0;

  /** Timestamp of the last sample read, so each average is stepped by the time that passed. */
  private long lastSampleAt = 0;

  private int shakeLevel = 1;
  private boolean triggered;

  public void reset() {
    forget();
    levelsSeeded = false;
  }

  public boolean accept(float deviation, long now, int level) {
    shakeLevel = Math.max(0, Math.min(2, level));
    triggered = false;
    updateLevels(deviation, now);
    if (warmingUp(now)) {
      forget();
      return false;
    }
    countSwings(deviation - background, now);
    watchForSettling(now);
    return triggered;
  }

  private void forget() {
    peakIndex = 0;
    peaksSeen = 0;
    lastPeakAt = 0;
    inPeak = false;
    armedAt = 0;
    stillSince = 0;
  }

  private void updateLevels(float deviation, long now) {
    final long elapsed = now - lastSampleAt;
    if (!levelsSeeded || elapsed < 0 || elapsed > SAMPLE_GAP_RESET_MILLIS) {
      background = deviation;
      recent = deviation;
      levelsSeeded = true;
      seededAt = now;
      frozenSince = 0;
      lastSampleAt = now;
      return;
    }
    lastSampleAt = now;
    // Time based rather than per sample: the accelerometer delivers at whatever rate it can,
    // and an average weighted by sample count would speed up and slow down with it.
    recent += (deviation - recent) * smoothing(elapsed, RECENT_TAU_MILLIS);

    if (warmingUp(now)) {
      background = recent;
      frozenSince = 0;
      return;
    }
    if (gestureUnderWay(now)) {
      if (frozenSince == 0) frozenSince = now;
      if (now - frozenSince <= BACKGROUND_FREEZE_LIMIT_MILLIS) return;
      // Whatever this is, it has gone on too long to be somebody shaking a phone. Drop it
      // and let the background be the movement the phone is actually living with now.
      forget();
      background = recent;
      frozenSince = 0;
      return;
    }
    frozenSince = 0;
    background += (deviation - background) * smoothing(elapsed, BACKGROUND_TAU_MILLIS);
  }

  /** True while the background is still finding the level of whatever the phone is doing. */
  private boolean warmingUp(long now) {
    return now - seededAt < WARMUP_MILLIS;
  }

  /**
   * How much of the way an average moves towards a sample that arrived this long after the last.
   */
  private static float smoothing(long elapsedMillis, float tauMillis) {
    return 1f - (float) Math.exp(-elapsedMillis / tauMillis);
  }

  /** True from the first swing of a shake until the gesture is answered, timed out or forgotten. */
  private boolean gestureUnderWay(long now) {
    if (armedAt != 0 || inPeak) return true;
    // Swings too far apart to ever form a shake between them let the background move again.
    return peaksSeen > 0 && now - lastPeakAt <= SHAKE_WINDOW_MILLIS;
  }

  /**
   * Counts a swing each time the movement rises this far past the background, and arms the trigger
   * once enough of them have happened close together. Requiring it to fall well back towards the
   * background in between is what makes one swing one count rather than a dozen.
   *
   * @param excess how far above the background the phone is moving, m/s².
   */
  private void countSwings(float excess, long now) {
    final float threshold = SHAKE_THRESHOLDS[shakeLevel];
    if (peaksSeen > 0 && !inPeak && armedAt == 0 && now - lastPeakAt > SHAKE_WINDOW_MILLIS) {
      // Nothing counted here can still be part of a shake, and holding on to it would keep
      // the background frozen on the strength of a knock hours ago.
      peaksSeen = 0;
      peakIndex = 0;
    }
    if (!inPeak && excess >= threshold) {
      inPeak = true;
      lastPeakAt = now;
      peakAt[peakIndex] = now;
      peakIndex = (peakIndex + 1) % SHAKE_PEAKS;
      ++peaksSeen;
      // peakAt[peakIndex] is now the oldest of the last SHAKE_PEAKS swings.
      if (peaksSeen >= SHAKE_PEAKS && now - peakAt[peakIndex] <= SHAKE_WINDOW_MILLIS) {
        // Shaking on and on keeps refreshing this, so the wait for the movement to settle
        // starts from the last swing rather than the first.
        armedAt = now;
        stillSince = 0;
      }
    } else if (inPeak && excess < threshold * 0.5f) {
      inPeak = false;
    }
  }

  /**
   * Fires the capture once the phone has been back to moving the way it did before the shake for
   * long enough. For a phone that was sitting still that is stillness; for one being walked with it
   * is the walk resumed, which is the whole point of measuring the change and not the movement.
   */
  private void watchForSettling(long now) {
    if (armedAt == 0) return;
    if (now - armedAt > ARMED_TIMEOUT_MILLIS) {
      // Shaken and then still being thrown about: whatever it was about, it is not this
      // moment.
      forget();
      return;
    }
    if (recent - background >= STILL_TOLERANCE + background * STILL_TOLERANCE_PER_BACKGROUND) {
      stillSince = 0;
      return;
    }
    if (stillSince == 0) {
      stillSince = now;
      return;
    }
    if (now - stillSince < STILL_MILLIS) return;

    forget();
    triggered = true;
  }
}
