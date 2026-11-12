package com.klob.echo;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;

final class Io {
  static void write(Path path, String text) throws IOException {
    Files.write(path, text.getBytes(StandardCharsets.UTF_8));
  }

  static void write(Path path, String text, Charset charset) throws IOException {
    Files.write(path, text.getBytes(charset));
  }

  static String read(Path path) throws IOException {
    try (InputStream in = Files.newInputStream(path)) {
      return new String(readBytes(in, 65536), StandardCharsets.UTF_8);
    }
  }

  static byte[] readBytes(InputStream in, int maximum) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = in.read(buffer)) != -1) {
      if (bytes.size() + count > maximum) throw new IOException("Input exceeds size limit");
      bytes.write(buffer, 0, count);
    }
    return bytes.toByteArray();
  }
}
