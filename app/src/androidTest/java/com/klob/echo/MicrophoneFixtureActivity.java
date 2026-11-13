package com.klob.echo;

/** Separate-UID foreground microphone owner used only for real priority-interruption validation. */
public final class MicrophoneFixtureActivity extends android.app.Activity {
  private android.media.AudioRecord recorder;
  private final android.content.BroadcastReceiver stopReceiver =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
          finish();
        }
      };
  private boolean registered;

  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    if (getIntent().getBooleanExtra("stop", false)) {
      finish();
      return;
    }
    registerStopReceiver();
    registered = true;
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
    new android.os.Handler(getMainLooper())
        .postDelayed(this::finish, getIntent().getLongExtra("duration_ms", 8000));
  }

  @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
  private void registerStopReceiver() {
    // This separate-UID fixture cannot load AndroidX from the target app. Before API 33,
    // platform receivers are exported by default and the explicit flags do not exist.
    android.content.IntentFilter filter =
        new android.content.IntentFilter("com.klob.echo.test.STOP_MIC_FIXTURE");
    if (android.os.Build.VERSION.SDK_INT >= 33)
      registerReceiver(stopReceiver, filter, RECEIVER_EXPORTED);
    else registerReceiver(stopReceiver, filter);
  }

  @Override
  public void onDestroy() {
    if (registered) unregisterReceiver(stopReceiver);
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
