package com.klob.echo.library;

import com.klob.echo.core.TimeWindow;
import java.io.File;

public final class RecordingInfo {
  public final File file;
  public final long timestamp, duration, size;
  public final int sampleRate;
  public final String kind, source, format;
  public final TimeWindow window;

  RecordingInfo(
      File file,
      long timestamp,
      long duration,
      int rate,
      String kind,
      String source,
      String format,
      boolean reliableTiming) {
    this.file = file;
    this.timestamp = timestamp;
    this.duration = duration;
    this.size = file.length();
    this.sampleRate = rate;
    this.kind = kind;
    this.source = source;
    this.format = format;
    this.window = reliableTiming ? new TimeWindow(timestamp, duration) : null;
  }
}
