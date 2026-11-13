package com.klob.echo.tools;

import android.media.*;
import java.io.*;
import java.nio.ByteBuffer;

/** Combines camera H.264 and Echo's shared microphone AAC without re-encoding the video. */
public final class VideoMuxer {
  public static void combine(File video, File audio, File output, int orientation)
      throws IOException {
    MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
    MediaMuxer muxer = null;
    boolean started = false;
    try {
      v.setDataSource(video.getPath());
      a.setDataSource(audio.getPath());
      MediaFormat vf = select(v, "video/"), af = select(a, "audio/");
      muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      muxer.setOrientationHint(orientation);
      int vt = muxer.addTrack(vf), at = muxer.addTrack(af);
      muxer.start();
      started = true;
      long end =
          vf.containsKey(MediaFormat.KEY_DURATION)
              ? vf.getLong(MediaFormat.KEY_DURATION)
              : Long.MAX_VALUE;
      copy(v, muxer, vt, end);
      copy(a, muxer, at, end);
      muxer.stop();
      started = false;
    } finally {
      v.release();
      a.release();
      if (muxer != null) {
        try {
          if (started) muxer.stop();
        } finally {
          muxer.release();
        }
      }
    }
  }

  private static MediaFormat select(MediaExtractor extractor, String prefix) throws IOException {
    for (int i = 0; i < extractor.getTrackCount(); i++) {
      MediaFormat format = extractor.getTrackFormat(i);
      if (format.getString(MediaFormat.KEY_MIME).startsWith(prefix)) {
        extractor.selectTrack(i);
        return format;
      }
    }
    throw new IOException("Missing " + prefix + " track");
  }

  private static void copy(MediaExtractor source, MediaMuxer target, int track, long end)
      throws IOException {
    ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1048576);
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    long first = -1;
    while (true) {
      long size = source.getSampleSize();
      if (size < 0) break;
      if (size > buffer.capacity()) throw new IOException("Video frame exceeds limit");
      buffer.clear();
      int n = source.readSampleData(buffer, 0);
      if (n < 0) break;
      long time = source.getSampleTime();
      if (first < 0) first = time;
      if (time - first >= end) break;
      info.set(
          0,
          n,
          time - first,
          (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
              ? MediaCodec.BUFFER_FLAG_KEY_FRAME
              : 0);
      target.writeSampleData(track, buffer, info);
      source.advance();
    }
  }
}
