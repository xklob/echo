package com.klob.echo.core;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class ListeningRulesTest {
  @Test
  public void overnightAndManualStop() {
    assertTrue(ListeningRules.within(1320, 360, 30));
    assertFalse(ListeningRules.within(1320, 360, 720));
    assertTrue(ListeningRules.within(360, 360, 720));
    assertFalse(ListeningRules.allowed(false, true, 0, 0, ZonedDateTime.now(), 100, 0));
    assertFalse(ListeningRules.allowed(true, false, 0, 0, ZonedDateTime.now(), 19, 20));
  }

  @Test
  public void dstTransitionsStayInLocalTimezone() {
    ZoneId zone = ZoneId.of("America/New_York");
    ZonedDateTime gap = ZonedDateTime.of(2026, 3, 8, 1, 59, 0, 0, zone);
    long next = ListeningRules.nextTransition(150, 600, gap);
    assertEquals(3, Instant.ofEpochMilli(next).atZone(zone).getHour());
    ZonedDateTime overlap = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone);
    assertTrue(
        ListeningRules.nextTransition(90, 600, overlap) > overlap.toInstant().toEpochMilli());
  }

  @Test
  public void clockFallbackCanReopenAnActiveWindow() {
    ZoneId zone = ZoneId.of("America/New_York");
    ZonedDateTime firstEnd =
        ZonedDateTime.of(2026, 11, 1, 1, 40, 0, 0, zone).withEarlierOffsetAtOverlap();
    long next = ListeningRules.nextTransition(30, 90, firstEnd);
    ZonedDateTime fallback = Instant.ofEpochMilli(next).atZone(zone);
    assertEquals(1, fallback.getHour());
    assertEquals(0, fallback.getMinute());
    assertTrue(ListeningRules.within(30, 90, fallback.getHour() * 60 + fallback.getMinute()));
    assertEquals(ZoneOffset.ofHours(-5), fallback.getOffset());
  }
}
