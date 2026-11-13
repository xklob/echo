package com.klob.echo.speech;

import android.media.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;

/** Streams native audio decoders into mono PCM16 at 16 kHz without loading a whole clip. */
public final class AudioDecoder {
  public interface Consumer {
    void accept(short[] pcm, int count) throws Exception;
  }

  public static void decode(File file, Consumer consumer) throws Exception {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    try {
      extractor.setDataSource(file.getPath());
      MediaFormat input = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat f = extractor.getTrackFormat(i);
        if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          input = f;
          extractor.selectTrack(i);
          break;
        }
      }
      if (input == null) throw new IOException("No audio track");
      codec = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
      codec.configure(input, null, null, 0);
      codec.start();
      int rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int encoding = AudioFormat.ENCODING_PCM_16BIT;
      boolean inputDone = false, outputDone = false;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      Resampler output = new Resampler(consumer);
      long lastProgress = SystemClock.elapsedRealtime();
      while (!outputDone) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!inputDone) {
          int index = codec.dequeueInputBuffer(10000);
          if (index >= 0) {
            ByteBuffer buffer = codec.getInputBuffer(index);
            int n = extractor.readSampleData(buffer, 0);
            if (n < 0) {
              codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(index, 0, n, extractor.getSampleTime(), 0);
              extractor.advance();
            }
            lastProgress = SystemClock.elapsedRealtime();
          }
        }
        int index = codec.dequeueOutputBuffer(info, 10000);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat f = codec.getOutputFormat();
          rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          encoding =
              f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                  ? f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                  : AudioFormat.ENCODING_PCM_16BIT;
          if (encoding != AudioFormat.ENCODING_PCM_16BIT
              && encoding != AudioFormat.ENCODING_PCM_FLOAT)
            throw new IOException("Unsupported decoded audio format");
        } else if (index >= 0) {
          try {
            ByteBuffer buffer = codec.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            int frameBytes = channels * (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2);
            while (buffer.remaining() >= frameBytes) {
              double sum = 0;
              for (int c = 0; c < channels; c++)
                sum +=
                    encoding == AudioFormat.ENCODING_PCM_FLOAT
                        ? buffer.getFloat() * 32768
                        : buffer.getShort();
              output.accept(Math.max(-32768, Math.min(32767, sum / channels)), rate);
            }
          } finally {
            codec.releaseOutputBuffer(index, false);
          }
          outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          lastProgress = SystemClock.elapsedRealtime();
        }
        if (SystemClock.elapsedRealtime() - lastProgress > 30000)
          throw new IOException("Audio decoder stalled");
      }
      output.flush();
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
      }
      extractor.release();
    }
  }

  /** Area-weighted resampling preserves time across decoder chunk boundaries. */
  private static final class Resampler {
    final Consumer consumer;
    final short[] pcm = new short[4096];
    int count;
    double filled, sum;

    Resampler(Consumer consumer) {
      this.consumer = consumer;
    }

    void accept(double sample, int rate) throws Exception {
      double remaining = 16000.0 / rate;
      while (remaining > 1e-9) {
        double take = Math.min(1 - filled, remaining);
        sum += sample * take;
        filled += take;
        remaining -= take;
        if (filled >= 1 - 1e-9) {
          pcm[count++] = (short) Math.round(sum);
          filled = sum = 0;
          if (count == pcm.length) flush();
        }
      }
    }

    void flush() throws Exception {
      if (count > 0) {
        consumer.accept(pcm, count);
        count = 0;
      }
    }
  }
}
