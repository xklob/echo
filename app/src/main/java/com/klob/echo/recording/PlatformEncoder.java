package com.klob.echo.recording;

import android.media.*;
import com.klob.echo.core.*;
import java.io.*;
import java.nio.*;
import java.util.function.IntConsumer;

/**
 * Capacity-aware PCM input, sample-count timestamps, and bounded EOS draining. Adapts saidIT
 * 281e200 and Toldya's FLAC/AAC patterns, with full drain/release.
 */
public final class PlatformEncoder implements AutoCloseable, ClipEncoder.PcmConsumer {
  private MediaCodec codec;
  private MediaMuxer muxer;
  private OutputStream flac;
  private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
  private long samples;
  private int track;
  private boolean started, outputEos, inputEos;
  private final boolean isFlac;
  private final ClipEncoder.Cancellation cancel;

  public static boolean supported(String mime) {
    MediaFormat f = MediaFormat.createAudioFormat(mime, 48000, 1);
    return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f) != null;
  }

  public PlatformEncoder(File file, boolean isFlac, ClipEncoder.Cancellation cancel)
      throws IOException {
    this.isFlac = isFlac;
    this.cancel = cancel;
    try {
      String mime = isFlac ? MediaFormat.MIMETYPE_AUDIO_FLAC : MediaFormat.MIMETYPE_AUDIO_AAC;
      MediaFormat format = MediaFormat.createAudioFormat(mime, 48000, 1);
      format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
      if (isFlac) {
        format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5);
        flac = new BufferedOutputStream(new FileOutputStream(file));
      } else {
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      }
      codec = MediaCodec.createEncoderByType(mime);
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      codec.start();
    } catch (Exception e) {
      release();
      throw new IOException("Cannot initialize export encoder", e);
    }
  }

  @Override
  public void write(short[] pcm, int offset, int count) throws IOException {
    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    while (count > 0) {
      check(deadline);
      int index = codec.dequeueInputBuffer(10000);
      if (index < 0) {
        drain(false);
        continue;
      }
      ByteBuffer buffer = codec.getInputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
      buffer.clear();
      int use = Math.min(count, buffer.remaining() / 2);
      if (use == 0) throw new IOException("Encoder input buffer has no PCM capacity");
      for (int i = 0; i < use; i++) buffer.putShort(pcm[offset + i]);
      codec.queueInputBuffer(index, 0, use * 2, samples * 1000000 / 48000, 0);
      samples += use;
      offset += use;
      count -= use;
      drain(false);
      deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    }
  }

  private void check(long deadline) throws IOException {
    if (cancel.cancelled()) throw new java.util.concurrent.CancellationException();
    if (android.os.SystemClock.elapsedRealtime() > deadline)
      throw new IOException("Export encoder timed out");
  }

  private void drain(boolean wait) throws IOException {
    while (true) {
      int index = codec.dequeueOutputBuffer(info, wait ? 10000 : 0);
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (!isFlac) {
          track = muxer.addTrack(codec.getOutputFormat());
          muxer.start();
          started = true;
        }
        continue;
      }
      if (index < 0) return;
      try {
        ByteBuffer buffer = codec.getOutputBuffer(index);
        if (info.size > 0) {
          buffer.position(info.offset);
          buffer.limit(info.offset + info.size);
          if (isFlac) {
            byte[] data = new byte[info.size];
            buffer.get(data);
            flac.write(data);
          } else if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            if (!started) throw new IOException("Encoder output preceded its format");
            muxer.writeSampleData(track, buffer, info);
          }
        }
        outputEos |= (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
      } finally {
        codec.releaseOutputBuffer(index, false);
      }
      if (outputEos) return;
    }
  }

  public void finish() throws IOException {
    long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
    while (!inputEos) {
      check(deadline);
      int index = codec.dequeueInputBuffer(10000);
      if (index >= 0) {
        codec.queueInputBuffer(
            index, 0, 0, samples * 1000000 / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        inputEos = true;
      }
      drain(true);
    }
    while (!outputEos) {
      check(deadline);
      drain(true);
    }
    if (flac != null) flac.flush();
  }

  private void release() throws IOException {
    try {
      if (codec != null) {
        try {
          codec.stop();
        } finally {
          codec.release();
        }
      }
    } finally {
      try {
        if (muxer != null) {
          try {
            if (started) muxer.stop();
          } finally {
            muxer.release();
          }
        }
      } finally {
        if (flac != null) flac.close();
      }
    }
  }

  @Override
  public void close() throws IOException {
    release();
  }

  public static void export(
      OpusHistory.Snapshot snapshot,
      File file,
      boolean flac,
      ClipEncoder.Cancellation cancel,
      IntConsumer progress)
      throws IOException {
    try (PlatformEncoder encoder = new PlatformEncoder(file, flac, cancel)) {
      ClipEncoder.pcm(snapshot, encoder, cancel, progress);
      encoder.finish();
    }
    if (flac) {
      // RFC 9639 STREAMINFO: low 36 bits of this field are total interchannel samples.
      try (RandomAccessFile metadata = new RandomAccessFile(file, "rw")) {
        if (metadata.readInt() != 0x664c6143 || (metadata.readUnsignedByte() & 127) != 0)
          throw new IOException("FLAC encoder omitted STREAMINFO");
        metadata.seek(18);
        long field = metadata.readLong();
        metadata.seek(18);
        metadata.writeLong((field & 0xfffffff000000000L) | (snapshot.sampleCount & 0xfffffffffL));
      }
    }
  }
}
