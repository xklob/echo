package com.klob.echo.ui;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.*;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.R;
import com.klob.echo.recording.CaptureConflicts;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.settings.AppSettings;

public final class RecordFragment extends ServiceFragment {
  private TextView state, available, selection, estimate, notice, source;
  private LiveWaveformView waveform;
  private MaterialButton start, save, cancel;
  private MaterialSwitch continuous;
  private LinearProgressIndicator progress;
  private String lastMessage = "";
  private ChipGroup durations;
  private Chip customDuration;
  private TextView customLabel;
  private final ActivityResultLauncher<String> mic =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          granted -> {
            if (granted) startRecording();
            else permissionMessage();
          });
  private final ActivityResultLauncher<String> notifications =
      registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getParentFragmentManager()
        .setFragmentResultListener(
            "confirmed",
            this,
            (key, result) -> {
              if (result.getString("action", "").equals("start_microphone")) beginRecording();
            });
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    LinearLayout body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    LinearLayout recorder = Ui.card(body);
    source = Ui.text(recorder, R.string.source_mic, 14, true);
    state = Ui.text(recorder, R.string.ready, 22, false);
    state.setId(R.id.record_state);
    waveform = new LiveWaveformView(requireContext());
    LinearLayout.LayoutParams wave =
        new LinearLayout.LayoutParams(-1, Ui.dp(requireContext(), 112));
    wave.topMargin = Ui.dp(requireContext(), 12);
    wave.bottomMargin = Ui.dp(requireContext(), 20);
    recorder.addView(waveform, wave);
    Ui.text(recorder, R.string.live_waveform_help, 13, true);
    available = Ui.text(recorder, "0:00", 44, false);
    available.setId(R.id.record_available);
    Ui.text(recorder, R.string.history_ready, 14, true);
    start =
        Ui.button(
            recorder,
            R.string.record_start,
            true,
            () -> {
              if (service != null && service.isSessionActive()) service.stopListening();
              else startRecording();
            });
    start.setId(R.id.record_start_stop);
    Ui.text(recorder, R.string.record_intro, 14, true);
    LinearLayout clip = Ui.card(body);
    Ui.text(clip, R.string.save_recent, 20, false);
    Ui.text(clip, R.string.save_help, 14, true);
    ChipGroup chips = durations = new ChipGroup(requireContext());
    chips.setSingleSelection(true);
    chips.setSelectionRequired(true);
    clip.addView(chips);
    for (int seconds : new int[] {60, 300, 900}) {
      Chip chip = new Chip(requireContext());
      chip.setId(View.generateViewId());
      chip.setText(getString(R.string.minutes_short, seconds / 60));
      chip.setCheckable(true);
      chip.setEnsureMinTouchTargetSize(true);
      chips.addView(chip);
      chip.setOnClickListener(
          v -> {
            AppSettings.selectLookback(requireContext(), seconds, false);
            renderDuration();
          });
    }
    customDuration = new Chip(requireContext());
    customDuration.setId(R.id.record_custom_chip);
    customDuration.setCheckable(true);
    customDuration.setEnsureMinTouchTargetSize(true);
    chips.addView(customDuration);
    customDuration.setOnClickListener(
        v -> {
          AppSettings.selectLookback(
              requireContext(), AppSettings.customLookbackSeconds(requireContext()), true);
          renderDuration();
        });
    LinearLayout customRow =
        (LinearLayout)
            Ui.row(
                clip,
                R.string.duration_custom,
                R.string.duration_seconds,
                () ->
                    PreferenceDialog.number(
                            "custom_lookback_seconds",
                            R.string.duration_seconds,
                            R.string.custom_duration_help,
                            AppSettings.customLookbackSeconds(requireContext()),
                            1,
                            86400,
                            false)
                        .show(getParentFragmentManager(), "duration"));
    customRow.setId(R.id.record_duration);
    customLabel = (TextView) ((LinearLayout) customRow.getChildAt(0)).getChildAt(1);
    renderDuration();
    selection = Ui.text(clip, R.string.history_empty, 16, false);
    estimate = Ui.text(clip, "", 14, true);
    save =
        Ui.button(
            clip,
            R.string.save_clip,
            true,
            () -> {
              if (service != null)
                service.saveLookback(AppSettings.lookbackSeconds(requireContext()), "manual");
            });
    save.setId(R.id.record_save);
    save.setEnabled(false);
    progress = new LinearProgressIndicator(requireContext());
    progress.setId(R.id.clip_progress);
    progress.setMax(100);
    clip.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(requireContext(), 4)));
    progress.setVisibility(View.GONE);
    cancel =
        Ui.button(
            clip,
            R.string.cancel_save,
            false,
            () -> {
              if (service != null) service.cancelSave();
            });
    cancel.setVisibility(View.GONE);
    continuous =
        Ui.toggle(
            body,
            R.string.continuous,
            R.string.continuous_help,
            false,
            enabled -> {
              if (service != null && enabled != service.continuous) service.setContinuous(enabled);
            });
    notice = Ui.text(body, R.string.pending_settings, 14, true);
    notice.setId(R.id.history_pending);
    getParentFragmentManager()
        .setFragmentResultListener(
            "preference_saved",
            this,
            (k, b) -> {
              if (b.getString("key", "").equals("custom_lookback_seconds")) renderDuration();
            });
    return root;
  }

  private void renderDuration() {
    if (durations == null) return;
    int value = AppSettings.lookbackSeconds(requireContext());
    int custom = AppSettings.customLookbackSeconds(requireContext());
    Ui.setText(
        customDuration, getString(R.string.custom_duration_chip, Ui.duration(custom * 48000L)));
    Ui.setText(customLabel, getString(R.string.custom_duration_value, custom));
    int selected = customDuration.getId();
    if (!AppSettings.usesCustomLookback(requireContext())) {
      int[] presets = {60, 300, 900};
      for (int i = 0; i < presets.length; i++)
        if (presets[i] == value) selected = durations.getChildAt(i).getId();
    }
    durations.check(selected);
  }

  @Override
  public void onResume() {
    super.onResume();
    if (requireActivity().getIntent().getBooleanExtra("tile_start", false)) {
      requireActivity().getIntent().removeExtra("tile_start");
      startRecording();
    }
  }

  private void startRecording() {
    if (requireContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      mic.launch(Manifest.permission.RECORD_AUDIO);
      return;
    }
    String warning =
        CaptureConflicts.read(requireContext(), service == null ? 0 : service.audioSessionId)
            .microphoneWarning(requireContext());
    if (!warning.isEmpty()) {
      ConfirmDialog.show(
          getParentFragmentManager(),
          R.string.capture_conflict_title,
          warning,
          R.string.continue_capture,
          "start_microphone");
      return;
    }
    beginRecording();
  }

  private void beginRecording() {
    try {
      requireContext()
          .startForegroundService(
              new Intent(requireContext(), RecordingService.class)
                  .setAction(RecordingService.ACTION_START));
    } catch (RuntimeException e) {
      permissionMessage();
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
  }

  private void permissionMessage() {
    if (getView() != null)
      Snackbar.make(getView(), R.string.mic_required, Snackbar.LENGTH_LONG)
          .setAction(
              R.string.android_settings,
              v ->
                  startActivity(
                      new Intent(
                          android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName()))))
          .show();
  }

  @Override
  protected void render() {
    if (state == null || service == null) return;
    RecordingService s = service;
    Ui.setText(
        state,
        getString(
            s.isCapturing()
                ? R.string.recording
                : s.captureState.equals("Paused by battery rule")
                    ? R.string.paused_battery
                    : s.captureState.startsWith("Paused")
                        ? R.string.paused_schedule
                        : s.captureState.contains("unavailable")
                            ? R.string.unavailable
                            : s.captureState.equals("Starting")
                                ? R.string.starting
                                : R.string.ready));
    Ui.setText(
        source, getString(s.isPlaybackCapture() ? R.string.source_device : R.string.source_mic));
    Ui.setText(available, Ui.duration(s.availableSamples));
    Ui.setText(
        start,
        getString(
            s.isSessionActive()
                ? R.string.record_stop
                : s.isEnabled() ? R.string.record_resume : R.string.record_start));
    int chosen = AppSettings.lookbackSeconds(requireContext());
    long samples = Math.min(chosen * 48000L, s.availableSamples);
    long end = s.endSample;
    waveform.history(s.amplitudeHistory.snapshot(Math.max(0, end - samples), end, waveform.bars()));
    Ui.setText(
        selection,
        samples == 0
            ? getString(R.string.history_empty)
            : getString(
                R.string.clip_selection, Ui.duration(samples), Ui.duration(chosen * 48000L)));
    String format = prefs().getString("format", "opus");
    long bytes =
        samples
            * (format.equals("wav")
                ? 768000L
                : format.equals("flac")
                    ? 384000L
                    : format.equals("m4a") ? 96000L : prefs().getInt("bitrate", 32000))
            / (8 * 48000);
    Ui.setText(
        estimate,
        samples == 0
            ? ""
            : getString(
                R.string.clip_estimate,
                Ui.bytes(bytes),
                format.toUpperCase(java.util.Locale.ROOT)));
    save.setEnabled(samples > 0 && !s.isSaving());
    progress.setVisibility(s.isSaving() ? View.VISIBLE : View.GONE);
    progress.setProgress(s.progress);
    cancel.setVisibility(s.isSaving() ? View.VISIBLE : View.GONE);
    continuous.setEnabled(s.isSessionActive());
    if (continuous.isChecked() != s.continuous) continuous.setChecked(s.continuous);
    notice.setVisibility(
        prefs().getBoolean("history_settings_pending", false) ? View.VISIBLE : View.GONE);
    if (!s.message.isEmpty() && !s.message.equals(lastMessage)) {
      lastMessage = s.message;
      if (s.message.startsWith("Saved ") || s.message.equals(getString(R.string.clip_saved)))
        Snackbar.make(requireView(), R.string.clip_saved, Snackbar.LENGTH_LONG)
            .setAction(
                R.string.view_library, v -> ((MainActivity) requireActivity()).selectLibrary())
            .show();
      else message(s.message);
    }
  }
}
