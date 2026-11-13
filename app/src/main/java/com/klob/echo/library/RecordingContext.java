package com.klob.echo.library;

import com.klob.echo.core.TimeWindow;
import java.util.*;

public final class RecordingContext {
  public final RecordingInfo recording;
  public final List<TraceItem> attachments;
  public final List<List<LocationPoint>> segments;
  public final LocationPoint firstLocation, lastLocation;
  public final int locationCount;
  public final boolean unreadableLocations;

  RecordingContext(
      RecordingInfo recording,
      List<TraceItem> traces,
      List<List<LocationPoint>> allSegments,
      boolean unreadable) {
    this.recording = recording;
    unreadableLocations = unreadable;
    List<TraceItem> matches = new ArrayList<>();
    List<List<LocationPoint>> selected = new ArrayList<>();
    TimeWindow window = recording.window;
    if (window != null) {
      for (TraceItem t : traces)
        if (!t.type.equals("location") && window.overlaps(t.time, t.duration)) matches.add(t);
      Set<String> seen = new HashSet<>();
      for (List<LocationPoint> segment : allSegments) {
        if (segment.isEmpty()
            || segment.get(0).time >= window.end
            || segment.get(segment.size() - 1).time < window.start) continue;
        List<LocationPoint> part = null;
        for (LocationPoint point : segment) {
          if (!window.contains(point.time)) {
            part = null;
            continue;
          }
          String key = point.time + ":" + point.latitude + ":" + point.longitude;
          if (!seen.add(key)) continue;
          if (part == null) {
            part = new ArrayList<>();
            selected.add(part);
          }
          part.add(point);
        }
      }
    }
    matches.sort(Comparator.comparingLong(t -> t.time));
    selected.sort(Comparator.comparingLong(s -> s.get(0).time));
    attachments = Collections.unmodifiableList(matches);
    List<List<LocationPoint>> frozen = new ArrayList<>();
    int count = 0;
    LocationPoint first = null, last = null;
    for (List<LocationPoint> segment : selected) {
      frozen.add(Collections.unmodifiableList(segment));
      count += segment.size();
      for (LocationPoint point : segment) {
        if (first == null || point.time < first.time) first = point;
        if (last == null || point.time > last.time) last = point;
      }
    }
    segments = Collections.unmodifiableList(frozen);
    locationCount = count;
    firstLocation = first;
    lastLocation = last;
  }
}
