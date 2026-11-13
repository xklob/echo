package com.klob.echo.ui;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import com.klob.echo.R;
import com.klob.echo.core.CameraCommand;
import com.klob.echo.recording.CaptureConflicts;
import com.klob.echo.settings.AppSettings;
import com.klob.echo.speech.SpeechModels;
import com.klob.echo.tools.*;

public final class ToolDetailFragment extends ServiceFragment {
  private LinearLayout body;
  private String page, lastMessage;
  private TextView status, detail, backPhrase, frontPhrase, cameraDuration;
  private TextView cameraHardware;
  private long nextCameraCheck;
  private boolean[] cameras = {false, false};
  private MaterialButton modelDownload;
  private TextInputLayout url, token;
  private MaterialSwitch enabled;
  private MaterialButton primary, secondary;
  private ToolJobs jobs;
  private Intent pendingConsent;
  private int pendingResult;
  private boolean pendingPlayback;
  private final ActivityResultLauncher<String> cameraPermission =
      registerForActivityResult(
          new ActivityResultContracts.RequestPermission(),
          granted -> {
            if (service != null) service.tools.restoreConfigured();
            if (!granted) message(R.string.permission_help);
          });
  private final ActivityResultLauncher<Intent> screenshot =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> acceptConsent(result.getResultCode(), result.getData(), false));
  private final ActivityResultLauncher<Intent> playback =
      registerForActivityResult(
          new ActivityResultContracts.StartActivityForResult(),
          result -> acceptConsent(result.getResultCode(), result.getData(), true));

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) {
      pendingConsent = androidx.core.os.BundleCompat.getParcelable(state, "consent", Intent.class);
      pendingResult = state.getInt("consent_result");
      pendingPlayback = state.getBoolean("consent_playback");
    }
    jobs = new ViewModelProvider(this).get(ToolJobs.class);
    getParentFragmentManager()
        .setFragmentResultListener(
            "preference_saved",
            this,
            (key, result) -> {
              if (result.getString("key", "").startsWith("camera_") && service != null) {
                service.tools.restoreConfigured();
              }
            });
    getParentFragmentManager()
        .setFragmentResultListener(
            "confirmed",
            this,
            (key, result) -> {
              String action = result.getString("action", "");
              if (!ready()) return;
              if (action.equals("microphone")) service.useMicrophoneCapture();
              else if (action.equals("device"))
                playback.launch(
                    requireContext()
                        .getSystemService(MediaProjectionManager.class)
                        .createScreenCaptureIntent());
              else if (action.equals("screenshots"))
                screenshot.launch(
                    requireContext()
                        .getSystemService(MediaProjectionManager.class)
                        .createScreenCaptureIntent());
            });
  }

  @Override
  public View onCreateView(LayoutInflater i, ViewGroup container, Bundle state) {
    page = requireArguments().getString("page");
    body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    switch (page) {
      case "tool_speech":
        Ui.text(body, R.string.speech_settings_help, 16, true);
        Ui.toggle(
                body,
                R.string.auto_analyze_speech,
                R.string.auto_analyze_help,
                prefs().getBoolean("auto_analyze_speech", false),
                on -> AppSettings.save(requireContext(), "auto_analyze_speech", on, false))
            .setId(R.id.auto_analyze_speech);
        enabled =
            Ui.toggle(
                body,
                R.string.auto_transcribe,
                R.string.auto_transcribe_help,
                prefs().getBoolean("auto_transcribe", false),
                on -> {
                  if (on && !SpeechModels.installed(requireContext())) {
                    enabled.setChecked(false);
                    message(R.string.model_missing);
                    return;
                  }
                  AppSettings.save(requireContext(), "auto_transcribe", on, false);
                });
        enabled.setId(R.id.auto_transcribe);
        Ui.text(body, R.string.speech_offline_note, 14, true);
        detail = Ui.text(body, "", 14, true);
        speechModel();
        break;
      case "tool_camera":
        Ui.text(body, R.string.camera_detail, 16, true);
        cameraHardware = Ui.text(body, "", 14, true);
        enabled =
            Ui.toggle(
                body,
                R.string.camera_enable,
                0,
                prefs().getBoolean("voice_camera_enabled", false),
                on -> {
                  if (service != null) service.tools.setEnabled("camera", on, "");
                  else AppSettings.save(requireContext(), "voice_camera_enabled", on, false);
                  if (on && !hasCamera()) cameraPermission.launch(Manifest.permission.CAMERA);
                });
        enabled.setId(R.id.camera_enabled);
        detail = Ui.text(body, "", 14, true);
        primary =
            Ui.button(
                body,
                R.string.camera_now,
                true,
                () -> {
                  if (ready()) {
                    if (!prefs().getBoolean("voice_camera_enabled", false))
                      enabled.setChecked(true);
                    if (hasCamera() && service.tools.camera.isRunning())
                      service.tools.camera.captureNow("back");
                  }
                });
        secondary =
            Ui.button(
                body,
                R.string.camera_front_now,
                false,
                () -> {
                  if (ready()) {
                    if (!prefs().getBoolean("voice_camera_enabled", false))
                      enabled.setChecked(true);
                    if (hasCamera() && service.tools.camera.isRunning())
                      service.tools.camera.captureNow("front");
                  }
                });
        backPhrase =
            settingRow(
                R.string.camera_back_phrase,
                () ->
                    PreferenceDialog.phrase(false).show(getParentFragmentManager(), "back_phrase"));
        frontPhrase =
            settingRow(
                R.string.camera_front_phrase,
                () ->
                    PreferenceDialog.phrase(true).show(getParentFragmentManager(), "front_phrase"));
        cameraDuration =
            settingRow(
                R.string.camera_duration,
                () ->
                    PreferenceDialog.number(
                            "camera_seconds",
                            R.string.camera_duration,
                            R.string.camera_duration_help,
                            30,
                            5,
                            600,
                            false)
                        .show(getParentFragmentManager(), "camera_seconds"));
        speechModel();
        Ui.button(
            body,
            R.string.grant_permission,
            false,
            () -> {
              if (!hasCamera()) cameraPermission.launch(Manifest.permission.CAMERA);
              else message(R.string.saved);
            });
        break;
      case "tool_screen":
        Ui.text(body, R.string.screen_detail, 16, true);
        detail = Ui.text(body, "", 16, false);
        primary =
            Ui.button(
                body,
                R.string.screen_start,
                true,
                () -> {
                  if (ready()) {
                    if (service.tools.screen.isRunning()) service.tools.stopScreen();
                    else
                      ConfirmDialog.show(
                          getParentFragmentManager(),
                          R.string.screen_capture_title,
                          CaptureConflicts.read(requireContext(), service.audioSessionId)
                              .projectionWarning(requireContext()),
                          R.string.continue_capture,
                          "screenshots");
                  }
                });
        secondary =
            Ui.button(
                body,
                R.string.screen_now,
                false,
                () -> {
                  if (ready() && service.tools.screen.isRunning())
                    service.tools.screen.captureNow();
                });
        break;
      case "tool_source":
        Ui.text(body, R.string.source_detail, 16, true);
        detail = Ui.text(body, "", 20, false);
        primary = Ui.button(body, R.string.source_use_mic, true, () -> switchSource("microphone"));
        secondary =
            Ui.button(body, R.string.source_use_device, false, () -> switchSource("device"));
        break;
      case "tool_sync":
        buildSync(state);
        break;
      default:
        throw new IllegalArgumentException("Unknown tool page");
    }
    status = Ui.text(body, "", 14, true);
    return root;
  }

  @Override
  public void onViewCreated(View view, Bundle state) {
    super.onViewCreated(view, state);
    jobs.busy.observe(
        getViewLifecycleOwner(),
        busy -> {
          if (primary != null) primary.setEnabled(!busy);
          if (busy)
            Ui.setText(
                status,
                getString(modelDownload != null ? R.string.model_downloading : R.string.saving));
        });
    jobs.result.observe(
        getViewLifecycleOwner(),
        result -> {
          if (result == 0) return;
          Ui.setText(status, getString(result));
          if (result == R.string.model_installed && service != null)
            service.tools.restoreConfigured();
        });
  }

  private void buildSync(Bundle state) {
    Ui.text(body, R.string.sync_detail, 16, true);
    url =
        Ui.input(
            body,
            R.string.sync_url,
            state == null ? prefs().getString("sync_url", "") : state.getString("url", ""),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    url.getEditText().setSingleLine(true);
    url.setPlaceholderText(getString(R.string.sync_url_hint));
    token =
        Ui.input(
            body,
            R.string.sync_token,
            "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.getEditText().setSingleLine(true);
    token.getEditText().setSaveEnabled(false);
    token.setSaveEnabled(false);
    token.setHelperText(getString(R.string.sync_token_help));
    token.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
    primary = Ui.button(body, R.string.sync_save, true, this::saveServer);
    Ui.toggle(
        body,
        R.string.sync_auto,
        R.string.sync_auto_help,
        prefs().getBoolean("sync", false),
        on -> {
          if (on && prefs().getString("sync_url", "").isEmpty()) {
            message(R.string.sync_configure_first);
            for (int j = 0; j < body.getChildCount(); j++)
              if (body.getChildAt(j) instanceof MaterialSwitch)
                ((MaterialSwitch) body.getChildAt(j)).setChecked(false);
          } else AppSettings.save(requireContext(), "sync", on, false);
        });
    secondary =
        Ui.button(
            body,
            R.string.sync_now,
            false,
            () -> {
              if (prefs().getString("sync_url", "").isEmpty())
                message(R.string.sync_configure_first);
              else if (service != null) service.tools.sync.sync();
            });
  }

  private void saveServer() {
    String base;
    url.setError(null);
    token.setError(null);
    try {
      base = TraceSync.endpoint(url.getEditText().getText().toString());
    } catch (Exception e) {
      url.setError(getString(R.string.sync_invalid_url));
      return;
    }
    String value = token.getEditText().getText().toString();
    if (!value.isEmpty()
        && (value.length() < 32
            || value.length() > 4096
            || value.contains("\n")
            || value.contains("\r"))) {
      token.setError(getString(R.string.sync_invalid_token));
      return;
    }
    try {
      if (value.isEmpty() && SyncCredentials.get(requireContext()).isEmpty()) {
        token.setError(getString(R.string.sync_token_required));
        return;
      }
      if (!value.isEmpty()) SyncCredentials.set(requireContext(), value);
    } catch (Exception e) {
      token.setError(getString(R.string.sync_credential_error));
      return;
    }
    token.getEditText().setText("");
    prefs().edit().putString("sync_url", base).apply();
    message(R.string.sync_saved);
  }

  private TextView settingRow(int title, Runnable action) {
    LinearLayout row = (LinearLayout) Ui.row(body, title, "", action);
    return (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
  }

  private void speechModel() {
    Ui.heading(body, R.string.speech_model);
    Ui.text(body, R.string.model_help, 14, true);
    modelDownload = Ui.button(body, R.string.model_download, true, jobs::model);
  }

  private boolean hasCamera() {
    return requireContext().checkSelfPermission(Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED;
  }

  private boolean ready() {
    if (service == null || !service.isCapturing()) {
      message(R.string.needs_recording);
      return false;
    }
    return true;
  }

  private void switchSource(String action) {
    if (ready()) {
      CaptureConflicts.State conflicts =
          CaptureConflicts.read(requireContext(), service.audioSessionId);
      String warning =
          action.equals("device")
              ? conflicts.projectionWarning(requireContext())
              : conflicts.microphoneWarning(requireContext());
      ConfirmDialog.show(
          getParentFragmentManager(),
          R.string.source_switch_title,
          getString(R.string.source_switch_help) + (warning.isEmpty() ? "" : "\n\n" + warning),
          R.string.source_switch,
          action);
    }
  }

  @Override
  protected void render() {
    // Ignore messages from before this screen connected, including an empty initial message.
    if (service != null && lastMessage == null) lastMessage = service.message;
    consumeConsent();
    if (detail == null && status == null) return;
    if (modelDownload != null) {
      boolean installed = SpeechModels.installed(requireContext());
      Ui.setText(
          modelDownload, getString(installed ? R.string.model_installed : R.string.model_download));
      modelDownload.setEnabled(!installed && !Boolean.TRUE.equals(jobs.busy.getValue()));
    }
    if (page.equals("tool_speech")) {
      Ui.setText(
          detail,
          getString(
              SpeechModels.installed(requireContext())
                  ? R.string.model_installed
                  : R.string.model_missing));
    } else if (page.equals("tool_camera")) {
      if (android.os.SystemClock.elapsedRealtime() >= nextCameraCheck) {
        cameras = CameraRecorder.cameras(requireContext());
        nextCameraCheck = android.os.SystemClock.elapsedRealtime() + 2000;
      }
      Ui.setText(
          cameraHardware,
          getString(
              cameras[0] && cameras[1]
                  ? R.string.cameras_both
                  : cameras[0]
                      ? R.string.camera_back_only
                      : cameras[1] ? R.string.camera_front_only : R.string.camera_none));
      primary.setEnabled(cameras[0]);
      secondary.setEnabled(cameras[1]);
      Ui.setText(backPhrase, prefs().getString("camera_back_phrase", CameraCommand.BACK));
      Ui.setText(frontPhrase, prefs().getString("camera_front_phrase", CameraCommand.FRONT));
      Ui.setText(
          cameraDuration, getString(R.string.seconds_value, prefs().getInt("camera_seconds", 30)));
      String label = getString(R.string.off);
      if (prefs().getBoolean("voice_camera_enabled", false)) {
        if (!hasCamera()) label = getString(R.string.permission_needed);
        else if (!cameras[0] && !cameras[1]) label = getString(R.string.camera_none);
        else if (!SpeechModels.installed(requireContext()))
          label = getString(R.string.model_missing);
        else if (service == null || !service.isCapturing())
          label = getString(R.string.enabled_waiting);
        else if (service.isPlaybackCapture()) label = getString(R.string.camera_needs_mic);
        else if (service.tools.camera.isCapturing()) label = service.tools.camera.status();
        else if (!service.tools.camera.status().isEmpty()) label = service.tools.camera.status();
        else {
          switch (service.tools.commands.status()) {
            case LISTENING:
              label = getString(R.string.camera_listening);
              break;
            case LOADING:
              label = getString(R.string.model_loading);
              break;
            case ERROR:
              label = getString(R.string.model_error);
              break;
            default:
              label = getString(R.string.enabled_waiting);
          }
        }
      }
      Ui.setText(detail, label);
    } else if (page.equals("tool_screen")) {
      boolean active = service != null && service.tools.screen.isRunning();
      Ui.setText(detail, getString(active ? R.string.screen_active : R.string.screen_idle));
      Ui.setText(primary, getString(active ? R.string.screen_stop : R.string.screen_start));
      secondary.setEnabled(active);
    } else if (page.equals("tool_source")) {
      boolean device = service != null && service.isPlaybackCapture();
      Ui.setText(detail, getString(device ? R.string.source_device : R.string.source_mic));
      primary.setEnabled(device);
      secondary.setEnabled(!device);
    }
    if (service != null && !service.message.isEmpty() && !service.message.equals(lastMessage)) {
      lastMessage = service.message;
      // Do not carry an earlier tool's message into a newly opened detail screen.
      Ui.setText(status, service.message);
    }
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    if (pendingConsent != null) {
      out.putParcelable("consent", pendingConsent);
      out.putInt("consent_result", pendingResult);
      out.putBoolean("consent_playback", pendingPlayback);
    }
    if (url != null) out.putString("url", url.getEditText().getText().toString());
    super.onSaveInstanceState(out);
  }

  private void acceptConsent(int code, Intent data, boolean deviceAudio) {
    if (code != Activity.RESULT_OK || data == null) {
      message(R.string.projection_denied);
      return;
    }
    pendingConsent = data;
    pendingResult = code;
    pendingPlayback = deviceAudio;
    consumeConsent();
  }

  private void consumeConsent() {
    // Activity results can arrive before onStart has rebound after Android's app picker.
    if (service == null || pendingConsent == null) return;
    Intent consent = pendingConsent;
    pendingConsent = null;
    if (pendingPlayback) service.usePlaybackCapture(pendingResult, consent);
    else service.tools.screen(pendingResult, consent);
  }
}
