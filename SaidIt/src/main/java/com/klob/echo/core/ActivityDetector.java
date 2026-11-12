package com.klob.echo.core;

/** Amplitude activity, not a claim of speech classification. All timings use captured samples. */
public final class ActivityDetector {
  public static final class Event {
    public final long start, end;

    Event(long start, long end) {
      this.start = start;
      this.end = end;
    }
  }

  private final double threshold;
  private final long preRoll, postRoll;
  private long samples, candidate = -1, activeStart = -1, quiet;

  public ActivityDetector(int db, int preSeconds, int postSeconds) {
    threshold = Math.pow(10, db / 20.0) * 32768;
    preRoll = preSeconds * 48000L;
    postRoll = postSeconds * 48000L;
  }

  public Event process(double rms, int count) {
    samples += count;
    if (rms >= threshold) {
      quiet = 0;
      if (candidate < 0) candidate = samples - count;
      if (activeStart < 0 && samples - candidate >= 4800)
        activeStart = Math.max(0, candidate - preRoll);
    } else {
      candidate = -1;
      quiet += count;
      if (activeStart >= 0 && quiet >= postRoll) {
        Event e = new Event(activeStart, samples);
        activeStart = -1;
        quiet = 0;
        return e;
      }
    }
    // A sustained event rotates each five minutes so it cannot outlive bounded history.
    if (activeStart >= 0 && samples - activeStart >= 5 * 60 * 48000L) {
      Event e = new Event(activeStart, samples);
      activeStart = samples;
      return e;
    }
    return null;
  }
}
