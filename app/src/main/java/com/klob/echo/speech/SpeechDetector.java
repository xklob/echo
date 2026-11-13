package com.klob.echo.speech;

import ai.onnxruntime.*;
import android.content.Context;
import com.klob.echo.core.SpeechSegments;
import java.io.InputStream;
import java.util.*;

/** Silero VAD 6.2.3, independent of English transcription or loudness thresholds. */
public final class SpeechDetector implements AutoCloseable {
  private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
  private final OrtSession session;
  private final float[][] input = new float[1][576];
  private float[][][] state = new float[2][1][128];
  private int buffered;
  private long samples;
  private final SpeechSegments segments = new SpeechSegments();

  public SpeechDetector(Context context) throws Exception {
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        InputStream in = context.getAssets().open("models/silero_vad.onnx")) {
      options.setIntraOpNumThreads(1);
      options.setInterOpNumThreads(1);
      session =
          environment.createSession(com.klob.echo.storage.Io.readBytes(in, 4 * 1048576), options);
    }
  }

  public void accept(short[] pcm, int count) throws Exception {
    for (int i = 0; i < count; i++) {
      input[0][64 + buffered++] = pcm[i] / 32768f;
      samples++;
      if (buffered == 512) infer(512);
    }
  }

  private void infer(int valid) throws Exception {
    try (OnnxTensor audio = OnnxTensor.createTensor(environment, input);
        OnnxTensor memory = OnnxTensor.createTensor(environment, state);
        OnnxTensor rate =
            OnnxTensor.createTensor(
                environment, java.nio.LongBuffer.wrap(new long[] {16000}), new long[0]);
        OrtSession.Result result =
            session.run(Map.of("input", audio, "state", memory, "sr", rate))) {
      float probability = ((float[][]) result.get(0).getValue())[0][0];
      state = (float[][][]) result.get(1).getValue();
      segments.accept(probability, (samples - valid) * 1000 / 16000, samples * 1000 / 16000);
    }
    System.arraycopy(input[0], 512, input[0], 0, 64);
    buffered = 0;
  }

  public List<long[]> finish() throws Exception {
    if (buffered > 0) {
      int valid = buffered;
      Arrays.fill(input[0], 64 + buffered, 576, 0);
      infer(valid);
    }
    return segments.finish();
  }

  @Override
  public void close() throws OrtException {
    session.close();
  }
}
