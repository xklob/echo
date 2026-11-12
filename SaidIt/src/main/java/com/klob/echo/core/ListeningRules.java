package com.klob.echo.core;

import java.time.*;
import java.time.zone.*;
import java.util.List;

public final class ListeningRules {
  private ListeningRules() {}

  public static boolean within(int start, int end, int minute) {
    if (start == end) return true;
    return start < end ? minute >= start && minute < end : minute >= start || minute < end;
  }

  public static boolean allowed(
      boolean enabled,
      boolean scheduled,
      int start,
      int end,
      ZonedDateTime now,
      int battery,
      int minimum) {
    if (!enabled || battery >= 0 && battery < minimum) return false;
    return !scheduled || within(start, end, now.getHour() * 60 + now.getMinute());
  }

  public static long nextTransition(int start, int end, ZonedDateTime now) {
    Instant next = now.plusDays(2).toInstant();
    ZoneRules rules = now.getZone().getRules();
    for (int day = 0; day < 3; day++)
      for (int minute : new int[] {start, end}) {
        LocalDateTime boundary = now.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60);
        List<ZoneOffset> offsets = rules.getValidOffsets(boundary);
        if (offsets.isEmpty()) {
          Instant candidate = rules.getTransition(boundary).getInstant();
          if (candidate.isAfter(now.toInstant()) && candidate.isBefore(next)) next = candidate;
        } else
          for (ZoneOffset offset : offsets) {
            Instant candidate = boundary.toInstant(offset);
            if (candidate.isAfter(now.toInstant()) && candidate.isBefore(next)) next = candidate;
          }
      }
    // Reevaluate at the clock jump itself, including when an already-ended window reopens.
    ZoneOffsetTransition clockJump = rules.nextTransition(now.toInstant());
    if (clockJump != null && clockJump.getInstant().isBefore(next)) next = clockJump.getInstant();
    return next.toEpochMilli();
  }

  public static int minute(String value) {
    LocalTime time = LocalTime.parse(value);
    return time.getHour() * 60 + time.getMinute();
  }
}
