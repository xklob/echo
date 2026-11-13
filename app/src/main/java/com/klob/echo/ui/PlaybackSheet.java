package com.klob.echo.ui;

import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.klob.echo.R;
import java.io.File;

/** Playback is scoped to this visible sheet and always releases audio focus on exit. */
public final class PlaybackSheet extends BottomSheetDialogFragment {
  private MediaPlayer player;
  private AudioFocusRequest focus;
  private TextView time, status;
  private MaterialButton play;
  private Slider seek;
  private boolean prepared, touching, autoplay = true;
  private int position;
  private final Handler handler = new Handler(Looper.getMainLooper());

  static PlaybackSheet forFile(File file, String title) {
    PlaybackSheet d = new PlaybackSheet();
    Bundle b = new Bundle();
    b.putString("file", file.getPath());
    b.putString("title", title);
    d.setArguments(b);
    return d;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) {
      position = state.getInt("position");
      autoplay = state.getBoolean("playing", false);
    }
  }

  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle state) {
    LinearLayout body = Ui.column(requireContext());
    int pad = Ui.dp(requireContext(), 24);
    body.setPadding(pad, pad, pad, pad + Ui.dp(requireContext(), 16));
    Ui.text(body, requireArguments().getString("title"), 24, false);
    status = Ui.text(body, R.string.playback_loading, 14, true);
    time = Ui.text(body, "0:00 / 0:00", 18, false);
    seek = new Slider(requireContext());
    seek.setId(R.id.playback_seek);
    seek.setValueFrom(0);
    seek.setValueTo(1);
    seek.setContentDescription(getString(R.string.seek));
    seek.setEnabled(false);
    body.addView(seek);
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
    play = Ui.button(body, R.string.pause, true, this::toggle);
    play.setEnabled(false);
    Ui.button(body, R.string.close, false, this::dismiss);
    return body;
  }

  @Override
  public void onStart() {
    super.onStart();
    prepare();
  }

  private void prepare() {
    try {
      player = new MediaPlayer();
      MediaPlayer current = player;
      AudioAttributes attributes =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build();
      player.setAudioAttributes(attributes);
      player.setDataSource(requireArguments().getString("file"));
      player.setOnPreparedListener(
          p -> {
            if (p != player || !isAdded()) return;
            prepared = true;
            seek.setValueTo(Math.max(1, p.getDuration()));
            seek.setEnabled(true);
            p.seekTo(Math.min(position, p.getDuration()));
            play.setEnabled(true);
            Ui.setText(status, "");
            if (autoplay) resume();
            else Ui.setText(play, getString(R.string.play));
            handler.post(tick);
          });
      player.setOnCompletionListener(
          p -> {
            if (p == player) {
              position = 0;
              autoplay = false;
              Ui.setText(play, getString(R.string.play));
              releaseFocus();
            }
          });
      player.setOnErrorListener(
          (p, w, e) -> {
            if (p == player) {
              release();
              Ui.setText(status, getString(R.string.playback_failed));
            }
            return true;
          });
      player.prepareAsync();
    } catch (Exception e) {
      release();
      Ui.setText(status, getString(R.string.playback_failed));
    }
  }

  private void resume() {
    if (player == null || !prepared) return;
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    if (focus == null) {
      focus =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(attributes)
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change < 0) pause();
                  },
                  handler)
              .build();
      if (requireContext().getSystemService(AudioManager.class).requestAudioFocus(focus)
          != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        releaseFocus();
        Ui.setText(status, getString(R.string.playback_focus));
        return;
      }
    }
    player.start();
    autoplay = true;
    Ui.setText(play, getString(R.string.pause));
  }

  private void pause() {
    if (player != null && prepared) {
      player.pause();
      position = player.getCurrentPosition();
    }
    autoplay = false;
    Ui.setText(play, getString(R.string.play));
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
          Ui.setText(time, Ui.duration(current * 48L) + " / " + Ui.duration(duration * 48L));
          if (!touching) seek.setValue(Math.min(seek.getValueTo(), current));
          handler.postDelayed(this, 500);
        }
      };

  private void releaseFocus() {
    if (focus != null) {
      requireContext().getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
      focus = null;
    }
  }

  private void release() {
    handler.removeCallbacks(tick);
    if (player != null) {
      if (prepared) position = player.getCurrentPosition();
      player.release();
      player = null;
    }
    prepared = false;
    releaseFocus();
  }

  @Override
  public void onStop() {
    release();
    autoplay = false;
    super.onStop();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle out) {
    out.putInt("position", player != null && prepared ? player.getCurrentPosition() : position);
    out.putBoolean("playing", player != null && prepared && player.isPlaying());
    super.onSaveInstanceState(out);
  }
}
