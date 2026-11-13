package com.klob.echo.core;

/** A captured interval includes its start and excludes its end. */
public final class TimeWindow {
  public final long start, end;

  public TimeWindow(long start, long duration) {
    if (start <= 0 || duration <= 0 || duration > Long.MAX_VALUE - start)
      throw new IllegalArgumentException("Invalid recording interval");
    this.start = start;
    this.end = start + duration;
  }

  public boolean contains(long time) {
    return time >= start && time < end;
  }

  public boolean overlaps(long time, long duration) {
    if (duration <= 0) return contains(time);
    return time > 0 && duration <= Long.MAX_VALUE - time && time < end && time + duration > start;
  }
}
