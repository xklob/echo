package com.klob.echo.speech;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import org.vosk.Model;

/** One native English model shared by independent recognizers, with explicit ownership. */
public final class SpeechModels {
  private static Model model;
  private static int references;

  public static boolean installed(Context context) {
    return new File(context.getFilesDir(), "vosk-model/.ready").isFile();
  }

  public static synchronized Model acquire(Context context) throws IOException {
    if (!installed(context))
      throw new IOException("Download the English speech model in Settings first.");
    if (model == null) model = new Model(new File(context.getFilesDir(), "vosk-model").getPath());
    references++;
    return model;
  }

  public static synchronized void release() {
    if (references > 0 && --references == 0) {
      model.close();
      model = null;
    }
  }
}
