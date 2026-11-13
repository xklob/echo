package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class TimeWindowTest {
  @Test
  public void adjacentClipsDoNotShareBoundaryEvents() {
    TimeWindow first = new TimeWindow(1000, 1000), next = new TimeWindow(2000, 1000);
    assertTrue(first.contains(1000));
    assertTrue(first.contains(1999));
    assertFalse(first.contains(2000));
    assertTrue(next.contains(2000));
    assertFalse(first.contains(999));
  }

  @Test
  public void videosCanOverlapWithoutStartingInsideClip() {
    TimeWindow clip = new TimeWindow(2000, 1000);
    assertTrue(clip.overlaps(1500, 1000));
    assertTrue(clip.overlaps(2500, 1000));
    assertTrue(clip.overlaps(2500, 0));
    assertFalse(clip.overlaps(1000, 1000));
    assertFalse(clip.overlaps(3000, 1000));
    assertFalse(clip.overlaps(Long.MAX_VALUE - 10, 100));
  }

  @Test
  public void invalidIntervalsAreRejected() {
    for (long[] interval : new long[][] {{0, 1}, {1, 0}, {1, -1}, {Long.MAX_VALUE, 1}})
      assertThrows(IllegalArgumentException.class, () -> new TimeWindow(interval[0], interval[1]));
  }
}
