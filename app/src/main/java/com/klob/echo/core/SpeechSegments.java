package com.klob.echo.core;

import java.util.ArrayList;
import java.util.List;

/** Hysteresis, minimum speech duration and short pause merging for 32 ms VAD frames. */
public final class SpeechSegments {
  private final List<long[]> ranges = new ArrayList<>();
  private long candidate = -1, silence = -1, end;

  public void accept(float probability, long startMs, long endMs) {
    end = endMs;
    if (probability >= .5f) {
      if (candidate < 0) candidate = startMs;
      silence = -1;
    } else if (candidate >= 0 && probability < .35f) {
      if (silence < 0) silence = startMs;
      if (endMs - silence >= 300) {
        finish(silence);
      }
    }
  }

  private void finish(long stop) {
    if (stop - candidate >= 200) {
      long start = Math.max(0, candidate - 100), finish = stop + 100;
      if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] >= start)
        ranges.get(ranges.size() - 1)[1] = finish;
      else ranges.add(new long[] {start, finish});
    }
    candidate = silence = -1;
  }

  public List<long[]> finish() {
    if (candidate >= 0) finish(silence >= 0 ? silence : end);
    for (long[] range : ranges) range[1] = Math.min(end, range[1]);
    return ranges;
  }
}
