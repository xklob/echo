package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ShakeGestureTest {
  @Test
  public void walkingAloneAndSingleKnockDoNotTrigger() {
    ShakeGesture g = new ShakeGesture();
    for (long t = 1000; t < 12000; t += 20)
      assertFalse(g.accept(3 + (float) Math.sin(t / 100.0) * 2, t, 1));
    assertFalse(g.accept(40, 12000, 1));
    for (long t = 12020; t < 16000; t += 20) assertFalse(g.accept(3, t, 1));
  }

  @Test
  public void deliberateShakeSettlesBackIntoWalkingOrRest() {
    for (float background : new float[] {0, 4}) {
      ShakeGesture g = new ShakeGesture();
      for (long t = 1000; t < 5000; t += 20) assertFalse(g.accept(background, t, 1));
      boolean triggered = false;
      for (long t = 5000; t < 6200; t += 20)
        triggered |= g.accept(background + ((t - 5000) % 300 < 100 ? 30 : 0), t, 1);
      assertFalse(triggered);
      for (long t = 6200; t < 10000; t += 20) triggered |= g.accept(background, t, 1);
      assertTrue("shake did not settle at background=" + background, triggered);
    }
  }

  @Test
  public void sampleGapDoesNotCountOldSwings() {
    ShakeGesture g = new ShakeGesture();
    for (long t = 1000; t < 5000; t += 20) g.accept(0, t, 1);
    g.accept(40, 5000, 1);
    g.accept(0, 5020, 1);
    for (long t = 10000; t < 14000; t += 20) assertFalse(g.accept(0, t, 1));
  }
}
