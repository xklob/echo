package com.klob.echo.speech;

import com.klob.echo.storage.Io;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Durable results are sidecars of the original audio. Waveforms are compact binary peak pairs. */
public final class ClipAnalysis {
  public static final Object FILE_LOCK = new Object();
  public static final String[] SUFFIXES = {".analysis.json", ".wave", ".transcript.txt"};
  public boolean analyzed, transcribed;
  public long duration;
  public final List<long[]> speech = new ArrayList<>();
  public final List<Word> words = new ArrayList<>();

  public static final class Word {
    public final String text;
    public final long start, end;

    public Word(String text, long start, long end) {
      this.text = text;
      this.start = start;
      this.end = end;
    }
  }

  public static final class Waveform {
    public final int width;
    public final long samples;
    public final short[] lows, highs;

    public Waveform(int width, long samples, short[][] values) {
      this.width = width;
      this.samples = samples;
      lows = values[0];
      highs = values[1];
    }

    public long duration() {
      return samples * 1000 / 16000;
    }
  }

  public static File file(File audio, String suffix) {
    return new File(audio.getPath() + suffix);
  }

  public static ClipAnalysis read(File audio) {
    ClipAnalysis result = new ClipAnalysis();
    File source = file(audio, ".analysis.json");
    if (!source.isFile()) return result;
    try {
      JSONObject json = new JSONObject(Io.read(source.toPath(), 32 * 1048576));
      if (json.optLong("audio_bytes") != audio.length()
          || json.optLong("audio_modified") != audio.lastModified()) return result;
      result.analyzed = json.optBoolean("analyzed");
      result.transcribed = json.optBoolean("transcribed");
      result.duration = json.optLong("duration_ms");
      JSONArray spans = json.optJSONArray("speech");
      if (spans != null)
        for (int i = 0; i < spans.length(); i++) {
          JSONArray span = spans.getJSONArray(i);
          long a = span.getLong(0), b = span.getLong(1);
          if (a >= 0 && b > a && b <= result.duration) result.speech.add(new long[] {a, b});
        }
      JSONArray words = json.optJSONArray("words");
      if (words != null)
        for (int i = 0; i < words.length(); i++) {
          JSONArray word = words.getJSONArray(i);
          result.words.add(new Word(word.getString(0), word.getLong(1), word.getLong(2)));
        }
      return result;
    } catch (Exception ignored) {
      return new ClipAnalysis();
    }
  }

  public String transcript() {
    StringBuilder text = new StringBuilder();
    long prior = -1;
    for (Word word : words) {
      if (text.length() > 0) text.append(prior >= 0 && word.start - prior > 1500 ? '\n' : ' ');
      text.append(word.text);
      prior = word.end;
    }
    return text.toString();
  }

  public void write(File audio) throws Exception {
    JSONObject json = new JSONObject();
    json.put("version", 1)
        .put("audio_bytes", audio.length())
        .put("audio_modified", audio.lastModified());
    json.put("duration_ms", duration).put("analyzed", analyzed).put("transcribed", transcribed);
    json.put("speech_model", "Silero VAD 6.2.3").put("transcript_model", "Vosk small English 0.15");
    JSONArray spans = new JSONArray(), text = new JSONArray();
    for (long[] span : speech) spans.put(new JSONArray().put(span[0]).put(span[1]));
    for (Word word : words) text.put(new JSONArray().put(word.text).put(word.start).put(word.end));
    json.put("speech", spans).put("words", text);
    atomicText(file(audio, ".analysis.json"), json.toString());
    if (transcribed) atomicText(file(audio, ".transcript.txt"), transcript());
    File summary = file(audio, ".json");
    JSONObject metadata =
        summary.isFile() ? new JSONObject(Io.read(summary.toPath())) : new JSONObject();
    long milliseconds = 0;
    for (long[] span : speech) milliseconds += span[1] - span[0];
    metadata
        .put("speech_analyzed", analyzed)
        .put("speech_ms", milliseconds)
        .put("transcribed", transcribed);
    atomicText(summary, metadata.toString());
  }

  public static void atomicText(File target, String value) throws IOException {
    File partial = new File(target.getPath() + ".part");
    try {
      Io.write(partial.toPath(), value);
      Files.move(
          partial.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      partial.delete();
    }
  }

  public static void writeWaveform(File audio, Waveform waveform) throws IOException {
    File target = file(audio, ".wave"), partial = file(audio, ".wave.part");
    try {
      try (DataOutputStream out =
          new DataOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
        out.writeInt(0x45434831);
        out.writeLong(audio.length());
        out.writeLong(audio.lastModified());
        out.writeInt(waveform.width);
        out.writeLong(waveform.samples);
        out.writeInt(waveform.lows.length);
        for (int i = 0; i < waveform.lows.length; i++) {
          out.writeShort(waveform.lows[i]);
          out.writeShort(waveform.highs[i]);
        }
      }
      Files.move(
          partial.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      partial.delete();
    }
  }

  public static Waveform readWaveform(File audio) {
    try (DataInputStream in =
        new DataInputStream(new BufferedInputStream(new FileInputStream(file(audio, ".wave"))))) {
      if (in.readInt() != 0x45434831
          || in.readLong() != audio.length()
          || in.readLong() != audio.lastModified()) return null;
      int width = in.readInt();
      long samples = in.readLong();
      int count = in.readInt();
      if (width < 160 || count < 0 || count > 262144 || samples < 0) return null;
      short[][] peaks = new short[2][count];
      for (int i = 0; i < count; i++) {
        peaks[0][i] = in.readShort();
        peaks[1][i] = in.readShort();
      }
      return new Waveform(width, samples, peaks);
    } catch (IOException ignored) {
      return null;
    }
  }
}
