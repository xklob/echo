package com.klob.echo.library;

import android.content.Context;
import com.klob.echo.storage.*;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

/** Joins independent local traces by captured time, without moving or taking ownership of them. */
public final class RecordingRepository {
  private final Context context;
  private final Map<String, CachedTrace> cache = new LinkedHashMap<>();

  private static final class CachedTrace {
    final long modified, size;
    final TraceItem item;

    CachedTrace(File file) {
      modified = file.lastModified();
      size = file.length();
      item = new TraceItem(file);
    }
  }

  public RecordingRepository(Context context) {
    this.context = context.getApplicationContext();
  }

  public List<RecordingContext> recordings() {
    List<RecordingInfo> recordings = new ArrayList<>();
    for (File file : new ClipStore(context).list()) recordings.add(info(file));
    recordings.sort(Comparator.comparingLong((RecordingInfo r) -> r.timestamp).reversed());
    return associate(recordings);
  }

  public RecordingContext recording(File file) {
    if (!validClip(file)) return null;
    return associate(Collections.singletonList(info(file))).get(0);
  }

  public List<TraceItem> traces() {
    List<TraceItem> items = new ArrayList<>();
    Set<String> present = new HashSet<>();
    for (File file : new TraceStore(context).list()) {
      String key = file.getPath();
      present.add(key);
      CachedTrace entry = cache.get(key);
      if (entry == null || entry.modified != file.lastModified() || entry.size != file.length()) {
        entry = new CachedTrace(file);
        cache.put(key, entry);
      }
      items.add(entry.item);
    }
    cache.keySet().retainAll(present);
    items.sort(Comparator.comparingLong((TraceItem t) -> t.time).reversed());
    return items;
  }

  private List<RecordingContext> associate(List<RecordingInfo> recordings) {
    List<TraceItem> traces = traces();
    List<List<LocationPoint>> segments = new ArrayList<>();
    boolean unreadable = false;
    long earliest = Long.MAX_VALUE, latest = 0;
    for (RecordingInfo r : recordings)
      if (r.window != null) {
        earliest = Math.min(earliest, r.window.start);
        latest = Math.max(latest, r.window.end);
      }
    if (latest > 0)
      for (TraceItem t : traces)
        if (t.type.equals("location")) {
          try {
            for (List<LocationPoint> segment : GpxReader.read(t.file)) {
              if (!segment.isEmpty()
                  && segment.get(0).time < latest
                  && segment.get(segment.size() - 1).time >= earliest) segments.add(segment);
            }
          } catch (IOException e) {
            unreadable = true;
          }
        }
    List<RecordingContext> result = new ArrayList<>();
    for (RecordingInfo r : recordings)
      result.add(new RecordingContext(r, traces, segments, unreadable));
    return result;
  }

  public boolean validClip(File file) {
    try {
      File root = new ClipStore(context).root.getCanonicalFile();
      File canonical = file.getCanonicalFile();
      return file.isFile()
          && ClipStore.isAudio(file.getName())
          && canonical.getParentFile() != null
          && root.equals(canonical.getParentFile().getParentFile())
          && Arrays.asList("manual", "auto", "continuous", "activity")
              .contains(canonical.getParentFile().getName());
    } catch (IOException e) {
      return false;
    }
  }

  private RecordingInfo info(File file) {
    long timestamp = file.lastModified(), duration = 0;
    int rate = 0;
    boolean reliable = false;
    String kind = file.getParentFile().getName(), source = "", format = extension(file);
    try (InputStream in = new FileInputStream(file.getPath() + ".json")) {
      JSONObject meta =
          new JSONObject(
              new String(Io.readBytes(in, 65536), java.nio.charset.StandardCharsets.UTF_8));
      long start = meta.optLong("start_time_ms", 0), samples = meta.optLong("captured_samples", 0);
      rate = meta.optInt("sample_rate", 0);
      double millis =
          samples > 0 && rate > 0
              ? samples * 1000.0 / rate
              : meta.optDouble("duration_seconds", 0) * 1000;
      if (start > 0
          && Double.isFinite(millis)
          && millis >= 1
          && millis <= 7 * 86400000L
          && start < Long.MAX_VALUE - (long) millis) {
        timestamp = start;
        duration = Math.round(millis);
        reliable = true;
      }
      source = meta.optString("source", "");
      kind = meta.optString("kind", kind);
    } catch (Exception ignored) {
    }
    if (duration == 0) duration = TraceItem.mediaDuration(file);
    return new RecordingInfo(file, timestamp, duration, rate, kind, source, format, reliable);
  }

  public static String extension(File file) {
    String name = file.getName();
    return name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
  }
}
