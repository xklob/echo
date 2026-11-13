package com.klob.echo.tools;

import com.klob.echo.recording.PlatformEncoder;
import java.io.*;
import java.util.concurrent.*;

/** AAC encoding of the existing microphone stream. A bounded queue never blocks audio history. */
final class CameraAudio {
  final File file;
  private final ArrayBlockingQueue<short[]> queue = new ArrayBlockingQueue<>(128);
  private final long startNanos, maximum;
  private final Thread thread;
  private volatile boolean closing;
  private volatile Exception failure;
  private boolean first = true;
  private long accepted;

  CameraAudio(File file, long startNanos, int seconds) {
    this.file = file;
    this.startNanos = startNanos;
    maximum = seconds * 48000L;
    thread = new Thread(this::encode, "cameraAudio");
    thread.start();
  }

  synchronized void feed(short[] pcm, int count, long endNanos) {
    if (closing || failure != null || accepted >= maximum) return;
    int offset = 0, leading = 0;
    if (first) {
      long from =
          Math.round((endNanos - count * 1000000000L / 48000 - startNanos) * 48000.0 / 1000000000);
      if (from + count <= 0) return;
      offset = (int) Math.max(0, -from);
      leading = (int) Math.min(maximum, Math.max(0, from));
      first = false;
    }
    int length = (int) Math.min(maximum - accepted, leading + count - offset);
    short[] copy = new short[length];
    System.arraycopy(pcm, offset, copy, leading, Math.max(0, length - leading));
    if (!queue.offer(copy)) failure = new IOException("Camera audio could not keep up");
    accepted += length;
  }

  synchronized void stopInput() {
    closing = true;
  }

  private void encode() {
    try (PlatformEncoder encoder = new PlatformEncoder(file, false, () -> failure != null)) {
      while (!closing || !queue.isEmpty()) {
        if (failure != null) throw failure;
        short[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
        if (pcm != null) encoder.write(pcm, 0, pcm.length);
      }
      if (accepted < 4800) throw new IOException("No microphone audio reached the camera");
      encoder.finish();
    } catch (Exception e) {
      failure = e;
    }
  }

  void finish() throws Exception {
    stopInput();
    thread.join(15000);
    if (thread.isAlive()) {
      failure = new IOException("Camera audio timed out");
      thread.interrupt();
      throw failure;
    }
    if (failure != null) throw failure;
  }
}
