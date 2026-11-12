package com.klob.echo.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ActivityDetectorTest {
  @Test
  public void quietAudioDoesNotTriggerAndOrdinaryNoiseCan() {
    ActivityDetector d = new ActivityDetector(-40, 1, 1);
    for (int i = 0; i < 50; i++) assertNull(d.process(10, 960));
    for (int i = 0; i < 10; i++) assertNull(d.process(500, 960));
    ActivityDetector.Event event = null;
    for (int i = 0; i < 50; i++) {
      ActivityDetector.Event next = d.process(0, 960);
      if (next != null) event = next;
    }
    assertNotNull(event);
    assertEquals(0, event.start);
    assertEquals(105600, event.end);
  }

  @Test
  public void briefTransientDoesNotActivate() {
    ActivityDetector d = new ActivityDetector(-40, 0, 1);
    assertNull(d.process(30000, 960));
    for (int i = 0; i < 100; i++) assertNull(d.process(0, 960));
  }
}
