package com.klob.echo.tools;

import android.content.Context;
import com.klob.echo.storage.Io;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.zip.*;

/** Installs the Apache-2.0 small US-English model only on an explicit UI action. */
public final class ModelInstaller {
  public static final String URL =
      "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

  public static synchronized File install(Context context) throws IOException {
    File target = new File(context.getFilesDir(), "vosk-model");
    if (new File(target, ".ready").isFile()) return target;
    File stage = new File(context.getFilesDir(), "vosk-install-" + java.util.UUID.randomUUID());
    if (!stage.mkdirs()) throw new IOException("Cannot create model staging directory");
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(URL).openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(30000);
      connection.setInstanceFollowRedirects(false);
      if (connection.getResponseCode() != 200)
        throw new IOException("Model server returned " + connection.getResponseCode());
      long expanded = 0;
      int entries = 0;
      try (ZipInputStream zip =
          new ZipInputStream(new BufferedInputStream(connection.getInputStream()))) {
        ZipEntry entry;
        byte[] buffer = new byte[32768];
        while ((entry = zip.getNextEntry()) != null) {
          if (++entries > 5000 || Thread.currentThread().isInterrupted())
            throw new IOException("Model installation cancelled or archive too large");
          File output = new File(stage, entry.getName());
          if (!output.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator))
            throw new IOException("Invalid model archive path");
          if (entry.isDirectory()) {
            if (!output.isDirectory() && !output.mkdirs())
              throw new IOException("Cannot unpack model");
            continue;
          }
          File parent = output.getParentFile();
          if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create model folder");
          try (OutputStream out = new FileOutputStream(output)) {
            int count;
            while ((count = zip.read(buffer)) != -1) {
              expanded += count;
              if (expanded > 256L * 1048576)
                throw new IOException("Model exceeds installation limit");
              out.write(buffer, 0, count);
            }
          }
        }
      }
      File model = new File(stage, "vosk-model-small-en-us-0.15");
      if (!new File(model, "am/final.mdl").isFile() || !new File(model, "conf/model.conf").isFile())
        throw new IOException("Downloaded model is incomplete");
      Io.write(
          new File(model, "MODEL-NOTICE.txt").toPath(),
          "Vosk small US-English model 0.15, Alpha Cephei. Apache-2.0. Source: "
              + URL
              + "\nLicense: https://www.apache.org/licenses/LICENSE-2.0\n");
      Io.write(new File(model, ".ready").toPath(), "vosk-model-small-en-us-0.15\n");
      if (target.exists()) deleteTree(target);
      Files.move(model.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
      return target;
    } finally {
      if (connection != null) connection.disconnect();
      deleteTree(stage);
    }
  }

  public static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }
}
