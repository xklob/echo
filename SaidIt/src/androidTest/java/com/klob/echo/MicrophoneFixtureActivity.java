package com.klob.echo;

/** Separate-UID foreground microphone owner used only for real priority-interruption validation. */
public final class MicrophoneFixtureActivity extends android.app.Activity {
  private android.media.AudioRecord recorder;

  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.widget.TextView label = new android.widget.TextView(this);
    label.setText("Generated microphone priority fixture");
    setContentView(label);
    try {
      recorder =
          new android.media.AudioRecord.Builder()
              .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
              .setPrivacySensitive(true)
              .setAudioFormat(
                  new android.media.AudioFormat.Builder()
                      .setSampleRate(48000)
                      .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                      .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(48000)
              .build();
      recorder.startRecording();
    } catch (RuntimeException e) {
      android.util.Log.e("EchoValidation", "Microphone fixture could not start", e);
    }
    new android.os.Handler(getMainLooper()).postDelayed(this::finish, 8000);
  }

  @Override
  public void onDestroy() {
    if (recorder != null) {
      try {
        recorder.stop();
      } catch (RuntimeException ignored) {
      }
      recorder.release();
      recorder = null;
    }
    super.onDestroy();
  }
}
