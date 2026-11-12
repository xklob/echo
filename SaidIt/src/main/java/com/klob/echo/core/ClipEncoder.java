package com.klob.echo.core;

import io.github.jaredmdobson.concentus.*;
import org.gagravarr.ogg.*;
import org.gagravarr.opus.*;
import java.io.*;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

/** Stream exports from pinned immutable packets. No speech classifier participates in saving. */
public final class ClipEncoder {
    private ClipEncoder() {}
    public interface PcmConsumer { void write(short[] samples, int offset, int count) throws IOException; }
    public interface Cancellation { boolean cancelled(); }
    private static void check(Cancellation cancellation) {
        if (cancellation.cancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException("Save cancelled");
    }
    public static void opus(OpusHistory.Snapshot snapshot, File file, Cancellation cancel, IntConsumer progress) throws IOException {
        // VorbisJava handles page CRC, BOS/EOS, stream serials, and 255-byte lacing.
        // The high-level OpusFile buffers entire clips, so use its streaming packet API.
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file)); OggFile ogg = new OggFile(out)) {
            OggPacketWriter writer = ogg.getPacketWriter();
            OpusInfo info = new OpusInfo(); info.setNumChannels(1); info.setSampleRate(OpusHistory.RATE); info.setPreSkip(snapshot.preSkip);
            writer.bufferPacket(info.write(), true);
            writer.bufferPacket(new OpusTags().write(), true);
            snapshot.read((packet, frame, last) -> {
                check(cancel);
                long granule = last ? snapshot.preSkip + snapshot.sampleCount : (frame + 1) * OpusHistory.FRAME;
                writer.bufferPacket(new OggPacket(packet), granule);
                if (!last) writer.flush();
                progress.accept((int) Math.min(99, Math.max(0, (granule - snapshot.preSkip) * 100 / snapshot.sampleCount)));
            });
            writer.close();
        }
    }
    public static void pcm(OpusHistory.Snapshot snapshot, PcmConsumer consumer, Cancellation cancel, IntConsumer progress) throws IOException {
        try {
            OpusDecoder decoder = new OpusDecoder(OpusHistory.RATE, 1);
            short[] samples = new short[OpusHistory.FRAME];
            long[] decoded = {0}, written = {0};
            snapshot.read((packet, frame, last) -> {
                check(cancel);
                int count;
                try { count = decoder.decode(packet, 0, packet.length, samples, 0, samples.length, false); }
                catch (OpusException e) { throw new IOException("Opus decode failed", e); }
                int skip = (int) Math.max(0, Math.min(count, snapshot.preSkip - decoded[0]));
                int use = (int) Math.min(count - skip, snapshot.sampleCount - written[0]);
                if (use > 0) { consumer.write(samples, skip, use); written[0] += use; }
                decoded[0] += count;
                progress.accept((int) (written[0] * 99 / snapshot.sampleCount));
            });
            if (written[0] != snapshot.sampleCount) throw new IOException("Truncated audio snapshot");
        } catch (OpusException e) { throw new IOException("Opus decoder unavailable", e); }
    }
    public static void wav(OpusHistory.Snapshot snapshot, File file, Cancellation cancel, IntConsumer progress) throws IOException {
        if (snapshot.sampleCount > (0xffffffffL - 36) / 2) throw new IOException("WAV exceeds RIFF limit; use Opus or shorter clips");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeBytes("RIFF"); le32(out, 36 + snapshot.sampleCount * 2); out.writeBytes("WAVEfmt ");
            le32(out, 16); le16(out, 1); le16(out, 1); le32(out, OpusHistory.RATE); le32(out, OpusHistory.RATE * 2);
            le16(out, 2); le16(out, 16); out.writeBytes("data"); le32(out, snapshot.sampleCount * 2);
            pcm(snapshot, (samples, offset, count) -> { for (int i = offset; i < offset + count; i++) le16(out, samples[i]); }, cancel, progress);
        }
    }
    private static void le16(DataOutputStream out, long value) throws IOException { out.writeByte((int) value); out.writeByte((int) (value >> 8)); }
    private static void le32(DataOutputStream out, long value) throws IOException { le16(out, value); le16(out, value >> 16); }
}
