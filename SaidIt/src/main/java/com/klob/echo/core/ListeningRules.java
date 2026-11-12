package com.klob.echo.core;

import java.time.*;

public final class ListeningRules {
    private ListeningRules() {}
    public static boolean within(int start, int end, int minute) {
        if (start == end) return true;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }
    public static boolean allowed(boolean enabled, boolean scheduled, int start, int end, ZonedDateTime now, int battery, int minimum) {
        if (!enabled || battery >= 0 && battery < minimum) return false;
        return !scheduled || within(start, end, now.getHour() * 60 + now.getMinute());
    }
    public static long nextTransition(int start, int end, ZonedDateTime now) {
        ZonedDateTime next = now.plusDays(2);
        for (int day = 0; day < 3; day++) for (int minute : new int[]{start, end}) {
            ZonedDateTime candidate = now.toLocalDate().plusDays(day).atTime(minute / 60, minute % 60).atZone(now.getZone());
            if (candidate.isAfter(now) && candidate.isBefore(next)) next = candidate;
        }
        return next.toInstant().toEpochMilli();
    }
    public static int minute(String value) {
        LocalTime time = LocalTime.parse(value); return time.getHour() * 60 + time.getMinute();
    }
}
