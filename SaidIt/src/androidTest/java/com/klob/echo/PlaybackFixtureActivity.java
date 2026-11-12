package com.klob.echo;

import android.media.*;

/** Another UID plays a generated tone and explicitly permits Android playback capture. */
public final class PlaybackFixtureActivity extends android.app.Activity {
  private volatile boolean playing = true;

  @Override
  public void onCreate(android.os.Bundle state) {
    super.onCreate(state);
    android.widget.TextView text = new android.widget.TextView(this);
    text.setText("Generated playback capture fixture");
    setContentView(text);
    new Thread(
            () -> {
              AudioTrack track =
                  new AudioTrack.Builder()
                      .setAudioAttributes(
                          new AudioAttributes.Builder()
                              .setUsage(AudioAttributes.USAGE_MEDIA)
                              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                              .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                              .build())
                      .setAudioFormat(
                          new AudioFormat.Builder()
                              .setSampleRate(48000)
                              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                              .build())
                      .setBufferSizeInBytes(19200)
                      .setTransferMode(AudioTrack.MODE_STREAM)
                      .build();
              try {
                track.play();
                short[] tone = new short[960];
                long frame = 0, deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                while (playing && android.os.SystemClock.elapsedRealtime() < deadline) {
                  for (int i = 0; i < tone.length; i++)
                    tone[i] = (short) (12000 * Math.sin(2 * Math.PI * 440 * (frame++) / 48000));
                  track.write(tone, 0, tone.length, AudioTrack.WRITE_BLOCKING);
                }
              } finally {
                track.release();
                runOnUiThread(this::finish);
              }
            },
            "Generated fixture tone")
        .start();
  }

  @Override
  public void onStop() {
    playing = false;
    super.onStop();
  }
}
