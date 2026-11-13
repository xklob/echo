package com.klob.echo.ui;

import android.content.Context;
import android.media.*;
import android.os.*;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.klob.echo.R;
import java.io.File;

/** Playback belongs to the visible recording detail and releases its player and focus on exit. */
final class AudioPlayback extends LinearLayout {
  private final File file;
  private MediaPlayer player;
  private AudioFocusRequest focus;
  private final TextView time, status;
  private final MaterialButton play;
  private final Slider seek;
  private boolean prepared, touching, autoplay;
  private int position;
  private final Handler handler = new Handler(Looper.getMainLooper());

  AudioPlayback(Context context, File file, Bundle state, boolean autoplay) {
    super(context);
    this.file = file;
    this.autoplay = autoplay;
    setOrientation(VERTICAL);
    setId(R.id.clip_player);
    if (state != null) {
      position = state.getInt("position");
      this.autoplay = state.getBoolean("playing", false);
    }
    time = Ui.text(this, "0:00 / 0:00", 22, false);
    status = Ui.text(this, R.string.playback_loading, 14, true);
    seek = new Slider(context);
    seek.setId(R.id.playback_seek);
    seek.setValueFrom(0);
    seek.setValueTo(1);
    seek.setContentDescription(context.getString(R.string.seek));
    seek.setEnabled(false);
    addView(seek);
    seek.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          public void onStartTrackingTouch(Slider slider) {
            touching = true;
          }

          public void onStopTrackingTouch(Slider slider) {
            touching = false;
            if (player != null && prepared) player.seekTo((int) slider.getValue());
          }
        });
    // Keyboard/accessibility changes also seek without needing a touch gesture.
    seek.addOnChangeListener(
        (slider, value, fromUser) -> {
          if (fromUser && !touching && player != null && prepared) player.seekTo((int) value);
        });
    play = Ui.button(this, R.string.clip_play, true, this::toggle);
    play.setId(R.id.clip_play_pause);
    play.setEnabled(false);
  }

  void start() {
    if (player != null) return;
    try {
      player = new MediaPlayer();
      player.setAudioAttributes(attributes());
      player.setDataSource(file.getPath());
      player.setOnPreparedListener(
          p -> {
            if (p != player) return;
            prepared = true;
            seek.setValueTo(Math.max(1, p.getDuration()));
            seek.setEnabled(true);
            p.seekTo(Math.min(position, p.getDuration()));
            play.setEnabled(true);
            status.setText("");
            status.setVisibility(GONE);
            if (autoplay) resume();
            else play.setText(R.string.clip_play);
            handler.post(tick);
          });
      player.setOnCompletionListener(
          p -> {
            if (p == player) {
              position = 0;
              autoplay = false;
              play.setText(R.string.clip_play);
              releaseFocus();
            }
          });
      player.setOnErrorListener(
          (p, w, e) -> {
            if (p == player) {
              stop();
              status.setText(R.string.playback_failed);
              status.setVisibility(VISIBLE);
            }
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      stop();
      status.setText(R.string.playback_failed);
      status.setVisibility(VISIBLE);
    }
  }

  private AudioAttributes attributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
  }

  private void resume() {
    if (player == null || !prepared) return;
    if (focus == null) {
      focus =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(attributes())
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change < 0) pause();
                  },
                  handler)
              .build();
      if (getContext().getSystemService(AudioManager.class).requestAudioFocus(focus)
          != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        releaseFocus();
        status.setText(R.string.playback_focus);
        status.setVisibility(VISIBLE);
        return;
      }
    }
    player.start();
    autoplay = true;
    play.setText(R.string.pause);
    status.setVisibility(GONE);
  }

  private void pause() {
    if (player != null && prepared) {
      player.pause();
      position = player.getCurrentPosition();
    }
    autoplay = false;
    play.setText(R.string.clip_play);
    releaseFocus();
  }

  private void toggle() {
    if (player == null || !prepared) return;
    if (player.isPlaying()) pause();
    else resume();
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (player == null || !prepared) return;
          int current = player.getCurrentPosition(), duration = player.getDuration();
          Ui.setText(time, LibraryText.duration(current) + " / " + LibraryText.duration(duration));
          if (!touching) seek.setValue(Math.min(seek.getValueTo(), current));
          handler.postDelayed(this, 500);
        }
      };

  private void releaseFocus() {
    if (focus != null) {
      getContext().getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
      focus = null;
    }
  }

  Bundle saveState() {
    Bundle b = new Bundle();
    b.putInt("position", player != null && prepared ? player.getCurrentPosition() : position);
    b.putBoolean("playing", player != null && prepared && player.isPlaying());
    return b;
  }

  void stop() {
    handler.removeCallbacks(tick);
    if (player != null) {
      if (prepared) position = player.getCurrentPosition();
      player.release();
      player = null;
    }
    prepared = false;
    autoplay = false;
    releaseFocus();
    play.setEnabled(false);
    seek.setEnabled(false);
  }
}
