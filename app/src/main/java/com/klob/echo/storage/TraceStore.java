package com.klob.echo.storage;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class TraceStore {
  public final File root;

  public TraceStore(Context context) {
    root = new File(context.getFilesDir(), "traces");
  }

  public File directory() {
    if (!root.isDirectory() && !root.mkdirs())
      throw new IllegalStateException("Cannot create traces folder");
    return root;
  }

  public static String timestampName(long millis) {
    return new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS", Locale.ROOT).format(new Date(millis));
  }

  public static File uniqueFile(File directory, String base, String extension) {
    try {
      return File.createTempFile(base + "-", extension + ".part", directory);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create trace", e);
    }
  }

  public File complete(File partial) throws IOException {
    File result = new File(partial.getParentFile(), partial.getName().replaceFirst("\\.part$", ""));
    Files.move(partial.toPath(), result.toPath(), StandardCopyOption.ATOMIC_MOVE);
    return result;
  }

  public File text(String suffix, String extension, String text) throws IOException {
    if (text.length() > 65536) throw new IOException("Trace text exceeds 64K characters");
    File partial =
        uniqueFile(directory(), timestampName(System.currentTimeMillis()) + suffix, extension);
    try {
      Io.write(partial.toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
      return complete(partial);
    } finally {
      partial.delete();
    }
  }

  public List<File> list() {
    File[] files = root.listFiles(f -> f.isFile() && !f.getName().endsWith(".part"));
    return files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
  }
}
