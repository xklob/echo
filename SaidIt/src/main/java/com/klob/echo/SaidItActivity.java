package com.klob.echo;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import java.text.DateFormat;
import java.util.Date;

/** One controls screen, based on Toldya's status/waveform and good-echo's flexible lookback. */
public final class SaidItActivity extends AppCompatActivity {
  private SaidItService service;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView state, details, selection, result;
  private MaterialButton listen, save, continuous, cancel;
  private ProgressBar progress;
  private SeekBar lookback;
  private EditText seconds;
  private WaveformView waveform;
  private boolean bound;
  private long selectedSeconds = 60;
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = ((SaidItService.LocalBinder) binder).service();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          service = null;
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) selectedSeconds = state.getLong("lookback_seconds", 60);
    LinearLayout body = Ui.screen(this);
    Ui.text(this, body, "Echo", 32);
    this.state = Ui.text(this, body, "Stopped", 22);
    waveform = new WaveformView(this);
    waveform.setContentDescription("Recent microphone amplitude");
    body.addView(waveform);
    details = Ui.text(this, body, "Available history // 0:00:00", 14);
    listen =
        Ui.button(
            this,
            body,
            "LISTEN",
            () -> {
              if (service != null && service.isSessionActive()) service.stopListening();
              else startListening();
            });
    Ui.text(this, body, "Save from the past", 20);
    selection = Ui.text(this, body, "", 14);
    lookback = new SeekBar(this);
    lookback.setMax(3600);
    lookback.setProgress(60);
    lookback.setContentDescription("Lookback in seconds");
    body.addView(lookback);
    seconds = new EditText(this);
    seconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    seconds.setHint("Lookback in seconds");
    seconds.setText(Long.toString(selectedSeconds));
    seconds.setSelectAllOnFocus(true);
    body.addView(seconds);
    seconds.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            try {
              selectedSeconds = Math.max(1, Math.min(86400, Long.parseLong(s.toString())));
            } catch (NumberFormatException ignored) {
            }
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    lookback.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int value, boolean user) {
            if (user) {
              selectedSeconds = Math.max(1, value);
              seconds.setText(Long.toString(selectedSeconds));
            }
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        });
    LinearLayout presets = new LinearLayout(this);
    body.addView(presets);
    for (long value : new long[] {60, 300, 900})
      Ui.button(
          this,
          presets,
          value / 60 + " MIN",
          () -> {
            selectedSeconds = value;
            seconds.setText(Long.toString(value));
            lookback.setProgress((int) value);
          });
    save =
        Ui.button(
            this,
            body,
            "SAVE",
            () -> {
              if (service != null) service.saveLookback(selectedSeconds, "manual");
            });
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    body.addView(progress);
    cancel =
        Ui.button(
            this,
            body,
            "CANCEL SAVE",
            () -> {
              if (service != null) service.cancelSave();
            });
    continuous =
        Ui.button(
            this,
            body,
            "START CONTINUOUS SAVE",
            () -> {
              if (service != null) service.setContinuous(!service.continuous);
            });
    result = Ui.text(this, body, "", 14);
    Ui.button(
        this, body, "RECORDINGS", () -> startActivity(new Intent(this, RecordingsActivity.class)));
    Ui.button(
        this,
        body,
        "PLAY LAST SAVED",
        () ->
            startActivity(new Intent(this, RecordingsActivity.class).putExtra("play_last", true)));
    Ui.button(
        this, body, "OPTIONAL TOOLS", () -> startActivity(new Intent(this, ToolsActivity.class)));
    Ui.button(
        this, body, "SETTINGS", () -> startActivity(new Intent(this, SettingsActivity.class)));
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putLong("lookback_seconds", selectedSeconds);
  }

  private void startListening() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 10);
      return;
    }
    try {
      startForegroundService(
          new Intent(this, SaidItService.class).setAction(SaidItService.ACTION_START));
    } catch (RuntimeException e) {
      result.setText("Could not start microphone. Open Echo and try again.");
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    if (code == 10) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startListening();
      else
        result.setText(
            "Microphone permission is required. You can allow it in Android app settings.");
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (getIntent().getBooleanExtra("tile_start", false)) {
      getIntent().removeExtra("tile_start");
      startListening();
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    bound = bindService(new Intent(this, SaidItService.class), connection, BIND_AUTO_CREATE);
    handler.post(update);
  }

  @Override
  protected void onStop() {
    handler.removeCallbacks(update);
    waveform.setActive(false);
    if (bound) {
      unbindService(connection);
      bound = false;
    }
    service = null;
    super.onStop();
  }

  private final Runnable update =
      new Runnable() {
        @Override
        public void run() {
          SaidItService s = service;
          if (s != null) {
            Ui.setText(
                state, s.captureState + s.tools.summary() + (s.isSaving() ? " // saving" : ""));
            Ui.setText(listen, s.isSessionActive() ? "STOP" : s.isEnabled() ? "RESUME" : "LISTEN");
            Ui.setText(
                details,
                "Available history // "
                    + Ui.duration(s.availableSamples)
                    + "\nRAM // "
                    + s.memoryBytes / 1048576
                    + " MB   Disk // "
                    + s.diskBytes / 1048576
                    + " MB");
            waveform.setAmplitudes(s.amplitudes);
            waveform.setActive(s.captureState.startsWith("Listening"));
            long samples = Math.min(selectedSeconds * 48000, s.availableSamples);
            long clipStart = s.startMs + Math.max(0, s.endSample - samples) * 1000 / 48000;
            String format =
                getSharedPreferences(SaidItService.PREFS, MODE_PRIVATE).getString("format", "opus");
            long bytes =
                samples
                    * (format.equals("wav")
                        ? 2
                        : format.equals("m4a")
                            ? 96000
                            : format.equals("flac")
                                ? 384000
                                : getSharedPreferences(SaidItService.PREFS, MODE_PRIVATE)
                                    .getInt("bitrate", 32000))
                    / (format.equals("wav") ? 1 : 8 * 48000);
            Ui.setText(
                selection,
                Ui.duration(samples)
                    + " // from "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                        .format(new Date(clipStart))
                    + "\n≈ "
                    + Math.max(1, bytes / 1024)
                    + " KB // "
                    + format.toUpperCase(java.util.Locale.ROOT));
            if (samples == 0) Ui.setText(selection, "No captured audio yet.");
            cancel.setVisibility(s.isSaving() ? android.view.View.VISIBLE : android.view.View.GONE);
            int maximum = (int) Math.max(60, Math.min(86400, s.availableSamples / 48000));
            if (lookback.getMax() != maximum) lookback.setMax(maximum);
            save.setEnabled(!s.isSaving() && s.availableSamples > 0);
            continuous.setEnabled(s.isSessionActive());
            Ui.setText(continuous, s.continuous ? "STOP CONTINUOUS SAVE" : "START CONTINUOUS SAVE");
            progress.setProgress(s.progress);
            progress.setVisibility(
                s.isSaving() ? android.view.View.VISIBLE : android.view.View.GONE);
            Ui.setText(result, s.message);
          }
          handler.postDelayed(this, 250);
        }
      };
}
