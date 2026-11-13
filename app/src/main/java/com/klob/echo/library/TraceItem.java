package com.klob.echo.library;

import android.media.MediaMetadataRetriever;
import com.klob.echo.storage.Io;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONObject;

public final class TraceItem {
  public final File file;
  public final String type, preview;
  public final long time, duration;

  public TraceItem(File file) {
    this.file = file;
    String name = file.getName();
    String kind =
        name.endsWith(".gpx")
            ? "location"
            : name.endsWith(".jpg")
                ? "image"
                : name.endsWith(".mp4")
                    ? "video"
                    : name.endsWith(".json")
                        ? "survey"
                        : name.contains("_keyword-") ? "keyword" : "note";
    long length = name.endsWith(".mp4") ? mediaDuration(file) : 0;
    long timestamp = utcTimestamp(name);
    if (timestamp <= 0) timestamp = Math.max(1, file.lastModified() - length);
    try (InputStream in = new FileInputStream(metadata(file))) {
      JSONObject meta =
          new JSONObject(
              new String(Io.readBytes(in, 16384), java.nio.charset.StandardCharsets.UTF_8));
      timestamp = meta.optLong("time_ms", timestamp);
      length = meta.optLong("duration_ms", length);
      String savedKind = meta.optString("type", kind);
      if (java.util.Arrays.asList("location", "image", "video", "survey", "keyword", "note")
          .contains(savedKind)) kind = savedKind;
    } catch (Exception ignored) {
    }
    type = kind;
    time = timestamp;
    duration = Math.max(0, length);
    String snippet = "";
    if (kind.equals("note") || kind.equals("keyword")) {
      try (InputStream in = new FileInputStream(file)) {
        byte[] bytes = new byte[512];
        int count = in.read(bytes);
        if (count > 0)
          snippet = new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8).strip();
        if (kind.equals("keyword") && snippet.contains(" // "))
          snippet = snippet.substring(snippet.indexOf(" // ") + 4);
        if (snippet.length() > 180) snippet = snippet.substring(0, 180) + "…";
      } catch (IOException ignored) {
      }
    }
    preview = snippet;
  }

  public static File metadata(File file) {
    return new File(file.getPath() + ".meta.json");
  }

  public JSONObject metadata() throws org.json.JSONException {
    return new JSONObject().put("time_ms", time).put("duration_ms", duration).put("type", type);
  }

  public static long utcTimestamp(String name) {
    try {
      if (name.length() < 24 || name.charAt(23) != 'Z') return 0;
      return LocalDateTime.parse(
              name.substring(0, 23),
              DateTimeFormatter.ofPattern("uuuu-MM-dd_HH.mm.ss.SSS", Locale.ROOT))
          .toInstant(ZoneOffset.UTC)
          .toEpochMilli();
    } catch (RuntimeException e) {
      return 0;
    }
  }

  public static long mediaDuration(File file) {
    try (MediaMetadataRetriever reader = new MediaMetadataRetriever()) {
      reader.setDataSource(file.getPath());
      return Math.max(
          0, Long.parseLong(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
    } catch (Exception e) {
      return 0;
    }
  }
}
