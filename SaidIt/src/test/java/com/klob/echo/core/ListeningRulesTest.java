package com.klob.echo.core;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;
public class ListeningRulesTest {
    @Test public void overnightAndManualStop() {
        assertTrue(ListeningRules.within(1320, 360, 30)); assertFalse(ListeningRules.within(1320, 360, 720));
        assertTrue(ListeningRules.within(360, 360, 720));
        assertFalse(ListeningRules.allowed(false, true, 0, 0, ZonedDateTime.now(), 100, 0));
        assertFalse(ListeningRules.allowed(true, false, 0, 0, ZonedDateTime.now(), 19, 20));
    }
    @Test public void dstTransitionsStayInLocalTimezone() {
        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime gap = ZonedDateTime.of(2026, 3, 8, 1, 59, 0, 0, zone);
        long next = ListeningRules.nextTransition(150, 600, gap);
        assertEquals(3, Instant.ofEpochMilli(next).atZone(zone).getHour());
        ZonedDateTime overlap = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, zone);
        assertTrue(ListeningRules.nextTransition(90, 600, overlap) > overlap.toInstant().toEpochMilli());
    }
}
