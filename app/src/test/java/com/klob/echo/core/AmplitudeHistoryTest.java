package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class AmplitudeHistoryTest {
  @Test
  public void previewUsesOnlySelectedAvailableWindowAndResizes() {
    AmplitudeHistory history = new AmplitudeHistory(10, 2, 4);
    history.accept(new short[] {30000, -30000, 2, -4, 6, -8, 10, -12}, 8);
    AmplitudeHistory.Snapshot view = history.snapshot(2, 8, 3);
    assertEquals(2, view.start);
    assertEquals(8, view.end);
    assertArrayEquals(new short[] {-4, -8, -12}, view.low);
    assertArrayEquals(new short[] {2, 6, 10}, view.high);
    assertEquals(6, history.snapshot(2, 8, 1000).low.length);
    assertEquals(2, history.snapshot(2, 8, 2).low.length);
  }

  @Test
  public void wrappedLongHistoryKeepsPeaksWithoutGrowing() {
    AmplitudeHistory history = new AmplitudeHistory(10, 2, 4);
    short[] old = new short[60];
    java.util.Arrays.fill(old, (short) 30000);
    history.accept(old, old.length);
    short[] recent = new short[40];
    java.util.Arrays.fill(recent, (short) -1234);
    history.accept(recent, recent.length);
    AmplitudeHistory.Snapshot view = history.snapshot(60, 100, 4);
    assertEquals(60, view.start);
    assertEquals(100, view.end);
    assertArrayEquals(new short[] {-1234, -1234, -1234, -1234}, view.low);
    assertArrayEquals(new short[4], view.high);
    assertEquals(60, history.snapshot(0, 100, 100).start);
  }

  @Test
  public void partialBuffersAndResetNeverReuseOldPeaks() {
    AmplitudeHistory history = new AmplitudeHistory(100, 2, 4);
    history.accept(new short[] {-10, 20, -30}, 3);
    history.accept(new short[] {40, -50}, 2);
    assertEquals(-50, history.snapshot(0, 5, 100).low[0]);
    assertEquals(40, history.snapshot(0, 5, 100).high[0]);
    history.reset();
    assertEquals(0, history.snapshot(0, 100, 10).low.length);
    history.accept(new short[] {2, 3}, 2);
    assertEquals(3, history.snapshot(0, 100, 10).high[0]);
    assertEquals(0, history.snapshot(0, 100, 10).low[0]);
  }
}
