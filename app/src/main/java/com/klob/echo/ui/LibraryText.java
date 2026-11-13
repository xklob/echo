package com.klob.echo.ui;

import android.content.Context;
import com.klob.echo.R;
import com.klob.echo.library.*;
import java.io.File;
import java.text.DateFormat;
import java.util.*;

final class LibraryText {
  static String date(long time) {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(new Date(time));
  }

  static String clock(long time) {
    return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(time));
  }

  static String timestamp(long time) {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        .format(new Date(time));
  }

  static String duration(long millis) {
    return Ui.duration(Math.max(0, millis) * 48L);
  }

  static String coordinates(LocationPoint point) {
    return String.format(Locale.getDefault(), "%.5f, %.5f", point.latitude, point.longitude);
  }

  static String source(Context c, String source) {
    return c.getString(
        source.equals("microphone")
            ? R.string.source_mic
            : source.equals("device_audio") ? R.string.source_device : R.string.source_unknown);
  }

  static String category(Context c, String kind) {
    return c.getString(
        kind.equals("auto")
            ? R.string.category_auto
            : kind.equals("continuous")
                ? R.string.category_continuous
                : kind.equals("activity") ? R.string.category_activity : R.string.category_manual);
  }

  static String base(File file) {
    String name = file.getName();
    return name.substring(0, name.lastIndexOf('.'));
  }

  static boolean generated(String name) {
    return name.startsWith("Echo-20") || name.startsWith("echo-20") || name.matches("20\\d\\d-.*");
  }

  static String title(Context c, RecordingInfo recording) {
    String name = base(recording.file);
    return generated(name) ? category(c, recording.kind) : name;
  }

  static String traceLabel(Context c, String type) {
    return c.getString(
        type.equals("image")
            ? R.string.screenshot_name
            : type.equals("video")
                ? R.string.video_name
                : type.equals("location")
                    ? R.string.location_name
                    : type.equals("keyword") ? R.string.keyword_event : R.string.saved_file);
  }

  static String traceTitle(Context c, TraceItem trace) {
    String base = base(trace.file);
    if (!generated(base)) return base;
    return traceLabel(c, trace.type);
  }

  static String context(Context c, RecordingContext recording) {
    List<String> pieces = new ArrayList<>();
    if (recording.locationCount > 0)
      pieces.add(c.getString(R.string.clip_location_preview, coordinates(recording.firstLocation)));
    if (!recording.attachments.isEmpty())
      pieces.add(
          c.getResources()
              .getQuantityString(
                  R.plurals.related_items,
                  recording.attachments.size(),
                  recording.attachments.size()));
    return String.join("\n", pieces);
  }

  static String preview(Context c, RecordingContext recording) {
    for (TraceItem t : recording.attachments)
      if (!t.preview.isEmpty()) return traceLabel(c, t.type) + " // " + t.preview;
    return "";
  }
}
