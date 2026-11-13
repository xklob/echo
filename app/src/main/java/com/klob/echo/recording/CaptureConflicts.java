package com.klob.echo.recording;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import com.klob.echo.R;

/**
 * Read-only preflight. Never opens a microphone or revokes another app's projection to probe it.
 */
public final class CaptureConflicts {
  // Android's remote-submix source. Normally filtered from public recording configurations.
  private static final int REMOTE_SUBMIX = 8;

  public static final class State {
    public final boolean microphone, deviceAudio, call, unknown;

    State(boolean microphone, boolean deviceAudio, boolean call, boolean unknown) {
      this.microphone = microphone;
      this.deviceAudio = deviceAudio;
      this.call = call;
      this.unknown = unknown;
    }

    public String microphoneWarning(Context context) {
      if (call) return context.getString(R.string.capture_call_warning);
      if (microphone) return context.getString(R.string.capture_mic_warning);
      if (unknown) return context.getString(R.string.capture_check_unavailable);
      return "";
    }

    public String projectionWarning(Context context) {
      String detected =
          deviceAudio ? context.getString(R.string.capture_device_detected) + "\n\n" : "";
      return detected + context.getString(R.string.capture_projection_warning);
    }
  }

  // The public @IntDef omits remote submix because AOSP filters it, but an OEM may expose it.
  @android.annotation.SuppressLint("WrongConstant")
  public static State read(Context context, int ownSession) {
    boolean mic = false, playback = false, call = false;
    try {
      AudioManager manager = context.getSystemService(AudioManager.class);
      if (manager == null) return new State(false, false, false, true);
      int mode = manager.getMode();
      call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
      for (AudioRecordingConfiguration recording : manager.getActiveRecordingConfigurations()) {
        if (ownSession > 0 && recording.getClientAudioSessionId() == ownSession) continue;
        if (recording.getClientAudioSource() == REMOTE_SUBMIX) playback = true;
        else mic = true;
      }
      return new State(mic, playback, call, false);
    } catch (RuntimeException e) {
      return new State(mic, playback, call, true);
    }
  }

  private CaptureConflicts() {}
}
