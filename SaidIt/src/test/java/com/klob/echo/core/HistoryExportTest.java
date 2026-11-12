package com.klob.echo.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class HistoryExportTest {
  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private OpusHistory history(long ram, long disk) throws Exception {
    return new OpusHistory(ram, disk, tmp.newFolder(), 32000);
  }

  private static short[] tone(int count, long start) {
    short[] pcm = new short[count];
    for (int i = 0; i < count; i++)
      pcm[i] = (short) (12000 * Math.sin(2 * Math.PI * 440 * (i + start) / 48000));
    return pcm;
  }

  private static void feed(OpusHistory h, int count) throws Exception {
    for (int n = 0; n < count; ) {
      int c = Math.min(1409, count - n);
      h.write(tone(c, n), c);
      n += c;
    }
  }

  @Test
  public void emptyHistoryCannotBeSaved() throws Exception {
    try (OpusHistory h = history(262144, 0)) {
      assertEquals(0, h.availableSamples());
      assertThrows(IOException.class, () -> h.snapshot(48000));
    }
  }

  @Test
  public void partialFramesAndEncoderDelayAreTrimmedExactly() throws Exception {
    for (int length : new int[] {7, 312, 960, 967, 48000, 480123}) {
      try (OpusHistory h = history(1024 * 1024, 0)) {
        feed(h, length);
        h.finish();
        assertEquals(length, h.endSample());
        try (OpusHistory.Snapshot s = h.snapshot(Long.MAX_VALUE)) {
          assertEquals(length, s.sampleCount);
          long[] samples = {0};
          ClipEncoder.pcm(s, (pcm, off, count) -> samples[0] += count, () -> false, p -> {});
          assertEquals(length, samples[0]);
        }
      }
    }
  }

  @Test
  public void ramWrapAndResizeReserveAreBoundedDuringSave() throws Exception {
    try (OpusHistory h = history(262144, 0)) {
      feed(h, 48000 * 40);
      long oldEnd = h.endSample();
      assertTrue(h.memoryBytes() < 262144 + 128000);
      try (OpusHistory.Snapshot s = h.snapshot(48000 * 10)) {
        assertThrows(IOException.class, () -> h.snapshot(1));
        CompletableFuture<Void> capture =
            CompletableFuture.runAsync(
                () -> {
                  try {
                    feed(h, 48000 * 40);
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                });
        File wav = tmp.newFile("concurrent.wav");
        ClipEncoder.wav(s, wav, () -> false, p -> {});
        capture.get(30, TimeUnit.SECONDS);
        assertEquals(44 + s.sampleCount * 2, wav.length());
        assertEquals(oldEnd, s.endSample);
        assertTrue(h.endSample() > oldEnd);
        assertTrue(h.memoryBytes() <= 2 * 262144 + 1024 * 1024);
      }
    }
  }

  @Test
  public void diskRotationPinsFilesAndDeletesAfterRelease() throws Exception {
    File directory = tmp.newFolder();
    try (OpusHistory h = new OpusHistory(262144, 262144, directory, 32000)) {
      feed(h, 48000 * 50);
      try (OpusHistory.Snapshot s = h.snapshot(48000 * 20)) {
        feed(h, 48000 * 50);
        assertTrue(h.diskBytes() <= 262144);
        long[] samples = {0};
        ClipEncoder.pcm(s, (pcm, off, count) -> samples[0] += count, () -> false, p -> {});
        assertEquals(s.sampleCount, samples[0]);
      }
      assertTrue(h.diskBytes() <= 262144);
    }
    assertEquals(0, Objects.requireNonNull(directory.list()).length);
  }

  @Test
  public void independentDecodeDurationChecksumAndSignal() throws Exception {
    File artifacts = new File("build/validation");
    assertTrue(artifacts.isDirectory() || artifacts.mkdirs());
    for (int length : new int[] {7, 960, 48017, 600123}) {
      try (OpusHistory h = history(1048576, 0)) {
        feed(h, length);
        h.finish();
        long lookback = length > 600000 ? 123457 : Long.MAX_VALUE;
        try (OpusHistory.Snapshot s = h.snapshot(lookback)) {
          File opus = new File(artifacts, "tone-" + length + ".opus");
          File wav = new File(artifacts, "tone-" + length + ".wav");
          ClipEncoder.opus(s, opus, () -> false, p -> {});
          ClipEncoder.wav(s, wav, () -> false, p -> {});
          byte[] data = Files.readAllBytes(opus.toPath());
          int position = 0, sequence = 0;
          while (position < data.length) {
            assertEquals(
                "OggS", new String(data, position, 4, java.nio.charset.StandardCharsets.US_ASCII));
            int segments = data[position + 26] & 255, size = 27 + segments;
            for (int i = 0; i < segments; i++) size += data[position + 27 + i] & 255;
            byte[] page = Arrays.copyOfRange(data, position, position + size);
            ByteBuffer little = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
            int stored = little.getInt(22);
            little.putInt(22, 0);
            int crc = 0;
            for (byte b : page) {
              crc ^= (b & 255) << 24;
              for (int bit = 0; bit < 8; bit++) crc = (crc << 1) ^ (crc < 0 ? 0x04c11db7 : 0);
            }
            assertEquals(stored, crc);
            assertEquals(sequence++, little.getInt(18));
            if (position == 0) assertEquals(2, page[5]);
            if (position + size == data.length) assertEquals(4, page[5]);
            position += size;
          }
          if (!new File("/usr/bin/ffmpeg").canExecute()) continue;
          File decoded = tmp.newFile();
          Process process =
              new ProcessBuilder(
                      "ffmpeg",
                      "-v",
                      "error",
                      "-xerror",
                      "-y",
                      "-i",
                      opus.getAbsolutePath(),
                      "-f",
                      "s16le",
                      decoded.getAbsolutePath())
                  .redirectError(ProcessBuilder.Redirect.INHERIT)
                  .start();
          assertEquals(0, process.waitFor());
          assertEquals(s.sampleCount * 2, decoded.length());
          if (s.sampleCount > 48000) {
            byte[] bytes = Files.readAllBytes(decoded.toPath());
            double error = 0, signal = 0;
            for (int i = 0; i < bytes.length / 2; i++) {
              short sample = (short) ((bytes[2 * i] & 255) | bytes[2 * i + 1] << 8);
              double target = 12000 * Math.sin(2 * Math.PI * 440 * (i + s.startSample) / 48000);
              error += Math.pow(sample - target, 2);
              signal += target * target;
            }
            assertTrue("Lossy signal differs excessively: " + error / signal, error / signal < .1);
          }
        }
      }
    }
  }

  @Test
  public void cancellationNeverReportsSuccessfulCompletion() throws Exception {
    try (OpusHistory h = history(262144, 0)) {
      feed(h, 48000);
      try (OpusHistory.Snapshot s = h.snapshot(48000)) {
        assertThrows(
            CancellationException.class,
            () -> ClipEncoder.opus(s, tmp.newFile(), () -> true, p -> {}));
      }
    }
  }

  @Test
  public void exact255BytePacketsHaveTerminatingZeroLace() throws Exception {
    for (int length : new int[] {1, 254, 255, 256, 510, 1275}) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (org.gagravarr.ogg.OggFile ogg = new org.gagravarr.ogg.OggFile(out)) {
        org.gagravarr.ogg.OggPacketWriter writer = ogg.getPacketWriter();
        writer.bufferPacket(new org.gagravarr.ogg.OggPacket(new byte[length]), 960L);
        writer.close();
      }
      byte[] page = out.toByteArray();
      int laces = page[26] & 255;
      assertEquals(length / 255 + 1, laces);
      assertEquals(length % 255, page[26 + laces] & 255);
      assertEquals(length, page.length - 27 - laces);
    }
  }

  @Test
  public void fillTriggerCountsOnlyNewDataAfterSave() throws Exception {
    try (OpusHistory history = history(262144, 0)) {
      feed(history, 48000 * 40);
      long cursor = history.writtenBudgetBytes();
      assertEquals(0.0, history.fillSince(cursor), 0.0);
      feed(history, 48000 * 2);
      assertTrue(history.fillSince(cursor) > 0);
      assertTrue(history.fillSince(cursor) < .75);
    }
  }
}
