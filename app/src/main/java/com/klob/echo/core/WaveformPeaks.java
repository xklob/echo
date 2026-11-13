package com.klob.echo.core;

import java.util.Arrays;

/** Starts at 10 ms resolution; compacts pairs to keep even long recordings bounded. */
public final class WaveformPeaks {
  private static final int LIMIT = 262144;
  private final short[] lows = new short[LIMIT], highs = new short[LIMIT];
  private int bins, count, width = 160;
  private short low, high;
  private long samples;

  public void accept(short[] pcm, int length) {
    for (int i = 0; i < length; i++) {
      low = (short) Math.min(low, pcm[i]);
      high = (short) Math.max(high, pcm[i]);
      samples++;
      if (++count == width) append();
    }
  }

  private void append() {
    if (bins == LIMIT) {
      for (int i = 0; i < bins / 2; i++) {
        lows[i] = (short) Math.min(lows[i * 2], lows[i * 2 + 1]);
        highs[i] = (short) Math.max(highs[i * 2], highs[i * 2 + 1]);
      }
      bins /= 2;
      width *= 2;
      // The pending bin was collected at the previous width. Keep collecting it.
      if (count < width) return;
    }
    lows[bins] = low;
    highs[bins++] = high;
    count = 0;
    low = high = 0;
  }

  public short[][] finish() {
    if (count > 0) {
      if (bins == LIMIT) append();
      if (count > 0) {
        lows[bins] = low;
        highs[bins++] = high;
        count = 0;
      }
    }
    return new short[][] {Arrays.copyOf(lows, bins), Arrays.copyOf(highs, bins)};
  }

  public int samplesPerBin() {
    return width;
  }

  public long samples() {
    return samples;
  }
}
