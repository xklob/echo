package com.klob.echo.core;

import io.github.jaredmdobson.concentus.*;
import java.io.*;
import java.util.*;

/**
 * One 48 kHz mono stream. Immutable ten-second blocks can be pinned by one exporter. Based on
 * good-echo's packet history and mikenrafter's disk rotation, with byte accounting. All mutation is
 * synchronized; codec access belongs to the capture thread.
 */
public final class OpusHistory implements Closeable {
  public static final int RATE = 48000, FRAME = 960;
  private static final int BLOCK_FRAMES = 500, ENTRY_OVERHEAD = 64, BLOCK_OVERHEAD = 512;
  private final OpusEncoder encoder;
  private final short[] pending = new short[FRAME];
  private final byte[] encoded = new byte[1275];
  private final Deque<Block> blocks = new ArrayDeque<>();
  private final File directory;
  private final long ramLimit, diskLimit;
  private final long allocationUnit;
  private final int delay;
  private long inputSamples,
      encodedFrames,
      residentBytes,
      diskBytes,
      activeRamBytes,
      activeDiskBytes,
      ramWritten,
      diskWritten;
  private int pendingCount;
  private Block current;
  private Snapshot activeSnapshot;
  private boolean finished;

  private static final class Block {
    long firstFrame, bytes = BLOCK_OVERHEAD;
    int count, pins;
    File file;
    long diskCost;
    List<byte[]> packets = new ArrayList<>();
    boolean retired;
  }

  public OpusHistory(long ramLimit, long diskLimit, File directory, int bitrate)
      throws IOException {
    this(ramLimit, diskLimit, directory, bitrate, 4096);
  }

  public OpusHistory(
      long ramLimit, long diskLimit, File directory, int bitrate, long allocationUnit)
      throws IOException {
    if (allocationUnit < 512 || allocationUnit > 65536)
      throw new IllegalArgumentException("Filesystem allocation unit");
    this.allocationUnit = allocationUnit;
    if (ramLimit < 256 * 1024 || diskLimit < 0)
      throw new IllegalArgumentException("History limits");
    this.ramLimit = ramLimit;
    this.diskLimit = diskLimit;
    this.directory = directory;
    if (diskLimit > 0 && (!directory.isDirectory() && !directory.mkdirs()))
      throw new IOException("Cannot create history directory");
    try {
      encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_AUDIO);
      encoder.setBitrate(bitrate);
      encoder.setComplexity(4);
      delay = encoder.getLookahead();
    } catch (OpusException e) {
      throw new IOException("Opus unavailable", e);
    }
  }

  public synchronized void write(short[] pcm, int count) throws IOException {
    if (finished || count < 0 || count > pcm.length)
      throw new IllegalStateException("Closed history or invalid input");
    int offset = 0;
    while (offset < count) {
      int n = Math.min(FRAME - pendingCount, count - offset);
      System.arraycopy(pcm, offset, pending, pendingCount, n);
      pendingCount += n;
      offset += n;
      inputSamples += n;
      if (pendingCount == FRAME) encodeFrame();
    }
  }

  /** Flush encoder lookahead, without counting zero padding as captured audio. */
  public synchronized void finish() throws IOException {
    if (finished) return;
    long required = inputSamples + delay;
    while (encodedFrames * FRAME < required) {
      Arrays.fill(pending, pendingCount, FRAME, (short) 0);
      encodeFrame();
    }
    finished = true;
  }

  private void encodeFrame() throws IOException {
    int length;
    try {
      length = encoder.encode(pending, 0, FRAME, encoded, 0, encoded.length);
    } catch (OpusException e) {
      throw new IOException("Opus encode failed", e);
    }
    if (current == null || current.count == BLOCK_FRAMES) {
      if (current != null && diskLimit > 0) seal(current);
      current = new Block();
      current.firstFrame = encodedFrames;
      blocks.addLast(current);
      residentBytes += BLOCK_OVERHEAD;
      activeRamBytes += BLOCK_OVERHEAD;
      ramWritten += BLOCK_OVERHEAD;
    }
    byte[] packet = Arrays.copyOf(encoded, length);
    ramWritten += length + ENTRY_OVERHEAD;
    diskWritten += length + 2;
    current.packets.add(packet);
    current.count++;
    current.bytes += length + ENTRY_OVERHEAD;
    residentBytes += length + ENTRY_OVERHEAD;
    activeRamBytes += length + ENTRY_OVERHEAD;
    encodedFrames++;
    pendingCount = 0;
    evict();
  }

  private void seal(Block block) throws IOException {
    if (block.file != null || block.pins > 0) return;
    File file = new File(directory, UUID.randomUUID() + ".packets");
    try (DataOutputStream out =
        new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      for (byte[] packet : block.packets) {
        out.writeShort(packet.length);
        out.write(packet);
      }
    } catch (IOException e) {
      file.delete();
      throw e;
    }
    block.file = file;
    block.diskCost = ((file.length() + allocationUnit - 1) / allocationUnit) * allocationUnit;
    diskBytes += block.diskCost;
    activeDiskBytes += block.diskCost;
    residentBytes -= block.bytes - BLOCK_OVERHEAD;
    activeRamBytes -= block.bytes - BLOCK_OVERHEAD;
    block.bytes = BLOCK_OVERHEAD;
    block.packets = null;
  }

  private void evict() throws IOException {
    // A snapshot has its own reserve equal to the history budget. Limits below are active history.
    while (blocks.size() > 1
        && (activeRamBytes > ramLimit || (diskLimit > 0 && activeDiskBytes > diskLimit / 2))) {
      Block b = blocks.removeFirst();
      activeRamBytes -= b.bytes;
      if (b.file != null) activeDiskBytes -= b.diskCost;
      b.retired = true;
      if (b.pins == 0) dispose(b);
    }
    if (residentBytes > 2 * ramLimit + 1024 * 1024 || (diskLimit > 0 && diskBytes > diskLimit))
      throw new IOException("Save is retaining more history than the configured reserve");
  }

  private void dispose(Block b) throws IOException {
    if (b.file != null) {
      long length = b.diskCost;
      if (!b.file.delete() && b.file.exists())
        throw new IOException("Cannot evict temporary history");
      diskBytes -= length;
    }
    residentBytes -= b.bytes;
  }

  public synchronized long availableSamples() {
    if (blocks.isEmpty()) return 0;
    long end = Math.min(inputSamples, encodedFrames * FRAME - delay);
    // Keep decoder warm-up inside history. Initial stream only needs encoder lookahead.
    long first = blocks.peekFirst().firstFrame;
    long start = first == 0 ? 0 : first * FRAME + 3840;
    return Math.max(0, end - start);
  }

  public synchronized long endSample() {
    return Math.max(0, Math.min(inputSamples, encodedFrames * FRAME - delay));
  }

  public synchronized long memoryBytes() {
    return residentBytes;
  }

  public synchronized long diskBytes() {
    return diskBytes;
  }

  public synchronized long writtenBudgetBytes() {
    return diskLimit > 0 ? diskWritten : ramWritten;
  }

  public synchronized double fillSince(long cursor) {
    return Math.max(
        0,
        Math.min(
            1,
            (writtenBudgetBytes() - cursor) / (double) (diskLimit > 0 ? diskLimit / 2 : ramLimit)));
  }

  public synchronized double fillRatio() {
    if (diskLimit > 0) return Math.min(1, diskBytes / (double) Math.max(1, diskLimit / 2));
    return Math.min(1, residentBytes / (double) ramLimit);
  }

  public synchronized Snapshot snapshot(long lookbackSamples) throws IOException {
    return snapshotRange(Math.max(0, endSample() - lookbackSamples), endSample());
  }

  public synchronized Snapshot snapshotRange(long requestedStart, long requestedEnd)
      throws IOException {
    if (activeSnapshot != null) throw new IOException("A save is already running");
    long end = Math.min(requestedEnd, endSample());
    long earliest = endSample() - availableSamples();
    long start = Math.max(earliest, requestedStart);
    if (end <= start) throw new IOException("No captured audio in this interval");
    long warmupFrame = Math.max(0, (start + delay - 3840) / FRAME);
    List<Block> selected = new ArrayList<>();
    for (Block b : blocks) {
      if (b.firstFrame + b.count <= warmupFrame || b.firstFrame * FRAME >= end + delay) continue;
      b.pins++;
      selected.add(b);
    }
    if (selected.isEmpty()) throw new IOException("History expired before save");
    Block last = selected.get(selected.size() - 1);
    // Freeze the mutable final block's packet references, not its audio arrays.
    List<byte[]> tail = last.packets == null ? null : new ArrayList<>(last.packets);
    Snapshot snapshot = new Snapshot(selected, tail, start, end, warmupFrame);
    activeSnapshot = snapshot;
    return snapshot;
  }

  public interface PacketConsumer {
    void accept(byte[] packet, long relativeFrame, boolean last) throws IOException;
  }

  public final class Snapshot implements AutoCloseable {
    private final List<Block> selected;
    private final List<byte[]> tail;
    public final long startSample, endSample;
    public final int preSkip;
    public final long sampleCount;
    private final long firstFrame, lastFrame;
    private boolean closed;

    Snapshot(List<Block> selected, List<byte[]> tail, long start, long end, long warmupFrame) {
      this.selected = selected;
      this.tail = tail;
      firstFrame = Math.max(selected.get(0).firstFrame, warmupFrame);
      lastFrame = (end + delay + FRAME - 1) / FRAME - 1;
      startSample = start;
      endSample = end;
      sampleCount = end - start;
      preSkip = (int) (start + delay - firstFrame * FRAME);
      if (preSkip > 65535) throw new IllegalStateException("Opus pre-skip overflow");
    }

    public void read(PacketConsumer consumer) throws IOException {
      if (closed) throw new IOException("Snapshot closed");
      for (int index = 0; index < selected.size(); index++) {
        Block b = selected.get(index);
        List<byte[]> packets = index == selected.size() - 1 && tail != null ? tail : b.packets;
        if (packets != null) {
          for (int i = 0; i < packets.size() && b.firstFrame + i <= lastFrame; i++)
            if (b.firstFrame + i >= firstFrame)
              consumer.accept(
                  packets.get(i), b.firstFrame + i - firstFrame, b.firstFrame + i == lastFrame);
        } else {
          try (DataInputStream in =
              new DataInputStream(new BufferedInputStream(new FileInputStream(b.file)))) {
            for (int i = 0; i < b.count && b.firstFrame + i <= lastFrame; i++) {
              int size = in.readUnsignedShort();
              if (size < 1 || size > 1275) throw new IOException("Corrupt temporary packet");
              byte[] packet = new byte[size];
              in.readFully(packet);
              if (b.firstFrame + i >= firstFrame)
                consumer.accept(
                    packet, b.firstFrame + i - firstFrame, b.firstFrame + i == lastFrame);
            }
          }
        }
      }
    }

    @Override
    public void close() throws IOException {
      synchronized (OpusHistory.this) {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Block b : selected) {
          b.pins--;
          if (b.retired && b.pins == 0)
            try {
              dispose(b);
            } catch (IOException e) {
              failure = e;
            }
        }
        activeSnapshot = null;
        if (failure != null) throw failure;
      }
    }
  }

  @Override
  public synchronized void close() throws IOException {
    for (Block b : blocks) {
      b.retired = true;
      if (b.pins == 0) dispose(b);
    }
    blocks.clear();
    activeRamBytes = 0;
    activeDiskBytes = 0;
    current = null;
    finished = true;
  }
}
