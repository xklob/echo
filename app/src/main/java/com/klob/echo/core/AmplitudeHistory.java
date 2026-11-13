package com.klob.echo.core;

/** Bounded peak envelopes for the recent audio preview. No PCM or audio files are retained here. */
public final class AmplitudeHistory {
  public static final class Snapshot {
    public final long start, end;
    public final short[] low, high;

    Snapshot(long start, long end, short[] low, short[] high) {
      this.start = start;
      this.end = end;
      this.low = low;
      this.high = high;
    }
  }

  private static final class Ring {
    final int width;
    final short[] low, high;
    long samples;

    Ring(int width, int bins) {
      this.width = width;
      low = new short[bins];
      high = new short[bins];
    }

    void accept(short[] pcm, int count) {
      int offset = 0;
      while (offset < count) {
        int bin = (int) ((samples / width) % low.length);
        int used = (int) (samples % width);
        if (used == 0) low[bin] = high[bin] = 0;
        int end = Math.min(count, offset + width - used);
        for (int i = offset; i < end; i++) {
          low[bin] = (short) Math.min(low[bin], pcm[i]);
          high[bin] = (short) Math.max(high[bin], pcm[i]);
        }
        samples += end - offset;
        offset = end;
      }
    }
  }

  private final Ring fine, coarse;

  public AmplitudeHistory() {
    this(48000, 600, 86400);
  }

  AmplitudeHistory(int rate, int fineSeconds, int coarseSeconds) {
    fine = new Ring(Math.max(1, rate / 10), fineSeconds * 10 + 1);
    coarse = new Ring(rate, coarseSeconds + 1);
  }

  public synchronized void reset() {
    fine.samples = coarse.samples = 0;
  }

  public synchronized void accept(short[] pcm, int count) {
    if (count < 0 || count > pcm.length) throw new IllegalArgumentException("Invalid PCM count");
    fine.accept(pcm, count);
    coarse.accept(pcm, count);
  }

  public synchronized Snapshot snapshot(long start, long end, int maximumBars) {
    end = Math.max(0, Math.min(end, coarse.samples));
    start = Math.max(0, Math.min(start, end));
    Ring ring = end - start <= (long) (fine.low.length - 1) * fine.width ? fine : coarse;
    long oldestBin = Math.max(0, ring.samples / ring.width - ring.low.length + 1);
    start = Math.min(end, Math.max(start, oldestBin * ring.width));
    if (end == start) return new Snapshot(start, end, new short[0], new short[0]);
    int bars =
        (int)
            Math.min(
                Math.max(1, Math.min(2048, maximumBars)),
                (end - start + ring.width - 1) / ring.width);
    short[] low = new short[bars], high = new short[bars];
    for (int i = 0; i < bars; i++) {
      long from = start + (end - start) * i / bars;
      long to = start + (end - start) * (i + 1) / bars;
      for (long b = from / ring.width; b <= (to - 1) / ring.width; b++) {
        int slot = (int) (b % ring.low.length);
        low[i] = (short) Math.min(low[i], ring.low[slot]);
        high[i] = (short) Math.max(high[i], ring.high[slot]);
      }
    }
    return new Snapshot(start, end, low, high);
  }
}
