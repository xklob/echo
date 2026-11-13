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
import com.klob.echo.settings.AppSettings;
import com.klob.echo.tools.*;
import java.io.File;

public final class ToolDetailFragment extends ServiceFragment {
  private LinearLayout body;
  private String page, lastMessage;
  private TextView status, detail, wordsLabel;
  private TextInputLayout note, url, token;
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
  private final ActivityResultLauncher<String[]> survey =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) jobs.importSurvey(uri);
          });

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
              if (result.getString("key", "").equals("keywords") && service != null) {
                service.tools.keywords.stop();
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
            });
  }

  @Override
  public View onCreateView(LayoutInflater i, ViewGroup container, Bundle state) {
    page = requireArguments().getString("page");
    body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    switch (page) {
      case "tool_keywords":
        Ui.text(body, R.string.keywords_help, 16, true);
        LinearLayout row =
            (LinearLayout)
                Ui.row(
                    body,
                    R.string.keyword_words,
                    prefs().getString("keywords", getString(R.string.keyword_no_words)),
                    () -> PreferenceDialog.keywords().show(getParentFragmentManager(), "words"));
        wordsLabel = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        enabled =
            Ui.toggle(
                body,
                R.string.keyword_enable,
                0,
                prefs().getBoolean("keywords_enabled", false),
                on -> {
                  String words = prefs().getString("keywords", "");
                  if (on && !com.klob.echo.recording.CaptureTools.validKeywords(words)) {
                    enabled.setChecked(false);
                    PreferenceDialog.keywords().show(getParentFragmentManager(), "words");
                    message(R.string.keyword_no_words);
                    return;
                  }
                  if (service != null) service.tools.setEnabled("keywords", on, words);
                  else AppSettings.save(requireContext(), "keywords_enabled", on, false);
                });
        enabled.setId(R.id.keyword_enabled);
        detail = Ui.text(body, "", 14, true);
        Ui.heading(body, R.string.keyword_model);
        Ui.text(body, R.string.model_help, 14, true);
        primary = Ui.button(body, R.string.model_download, true, jobs::model);
        break;
      case "tool_camera":
        Ui.text(body, R.string.camera_detail, 16, true);
        enabled =
            Ui.toggle(
                body,
                R.string.camera_enable,
                0,
                prefs().getBoolean("camera_enabled", false),
                on -> {
                  if (service != null) service.tools.setEnabled("camera", on, "");
                  else AppSettings.save(requireContext(), "camera_enabled", on, false);
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
                    if (!prefs().getBoolean("camera_enabled", false)) enabled.setChecked(true);
                    if (hasCamera() && service.tools.camera.isRunning())
                      service.tools.camera.captureNow();
                  }
                });
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
                      screenshot.launch(
                          requireContext()
                              .getSystemService(MediaProjectionManager.class)
                              .createScreenCaptureIntent());
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
      case "tool_note":
        note =
            Ui.input(
                body,
                R.string.note_hint,
                state == null ? "" : state.getString("note", ""),
                InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.getEditText().setId(R.id.note_text);
        note.getEditText().setMinLines(6);
        note.setCounterEnabled(true);
        note.setCounterMaxLength(65536);
        primary =
            Ui.button(
                body,
                R.string.note_save,
                true,
                () -> {
                  String text = note.getEditText().getText().toString();
                  if (text.trim().isEmpty()) note.setError(getString(R.string.note_empty));
                  else if (text.length() > 65536)
                    note.setError(getString(R.string.text_limit, 65536));
                  else {
                    note.setError(null);
                    jobs.note(text);
                  }
                });
        break;
      case "tool_survey":
        Ui.text(body, R.string.survey_detail, 16, true);
        detail = Ui.text(body, "", 18, false);
        primary =
            Ui.button(
                body,
                R.string.survey_begin,
                true,
                () -> DetailActivity.open(requireContext(), "survey_run", R.string.survey));
        secondary =
            Ui.button(
                body,
                R.string.survey_import,
                false,
                () -> survey.launch(new String[] {"application/json", "text/plain"}));
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
                getString(
                    page.equals("tool_keywords") ? R.string.model_downloading : R.string.saving));
        });
    jobs.result.observe(
        getViewLifecycleOwner(),
        result -> {
          if (result == 0) return;
          Ui.setText(status, getString(result));
          if (result == R.string.model_installed && service != null)
            service.tools.restoreConfigured();
          if (result == R.string.note_saved && note != null) {
            note.getEditText().setText("");
            jobs.result.setValue(0);
          }
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
    if (ready())
      ConfirmDialog.show(
          getParentFragmentManager(),
          R.string.source_switch_title,
          R.string.source_switch_help,
          R.string.source_switch,
          action);
  }

  @Override
  protected void render() {
    // Ignore messages from before this screen connected, including an empty initial message.
    if (service != null && lastMessage == null) lastMessage = service.message;
    consumeConsent();
    if (detail == null && status == null) return;
    if (page.equals("tool_keywords")) {
      String words = prefs().getString("keywords", "");
      Ui.setText(wordsLabel, words.isEmpty() ? getString(R.string.keyword_no_words) : words);
      boolean model = new File(requireContext().getFilesDir(), "vosk-model/.ready").isFile();
      boolean wanted = prefs().getBoolean("keywords_enabled", false);
      int label =
          !wanted
              ? R.string.off
              : !model
                  ? R.string.model_missing
                  : service == null || !service.isCapturing()
                      ? R.string.enabled_waiting
                      : service.tools.keywords.getStatus() == KeywordDetector.Status.LOADING
                          ? R.string.model_loading
                          : service.tools.keywords.getStatus() == KeywordDetector.Status.ERROR
                              ? R.string.model_error
                              : R.string.active;
      Ui.setText(detail, getString(label));
      Ui.setText(primary, getString(model ? R.string.model_installed : R.string.model_download));
      primary.setEnabled(!model && !Boolean.TRUE.equals(jobs.busy.getValue()));
    } else if (page.equals("tool_camera"))
      Ui.setText(
          detail,
          getString(
              !prefs().getBoolean("camera_enabled", false)
                  ? R.string.off
                  : !hasCamera()
                      ? R.string.permission_needed
                      : service != null && service.tools.camera.isRunning()
                          ? R.string.active
                          : R.string.enabled_waiting));
    else if (page.equals("tool_screen")) {
      boolean active = service != null && service.tools.screen.isRunning();
      Ui.setText(detail, getString(active ? R.string.screen_active : R.string.screen_idle));
      Ui.setText(primary, getString(active ? R.string.screen_stop : R.string.screen_start));
      secondary.setEnabled(active);
    } else if (page.equals("tool_source")) {
      boolean device = service != null && service.isPlaybackCapture();
      Ui.setText(detail, getString(device ? R.string.source_device : R.string.source_mic));
      primary.setEnabled(device);
      secondary.setEnabled(!device);
    } else if (page.equals("tool_survey")) {
      boolean exists = new File(requireContext().getFilesDir(), "survey.json").isFile();
      Ui.setText(detail, getString(exists ? R.string.survey_imported : R.string.survey_missing));
      primary.setEnabled(exists && !Boolean.TRUE.equals(jobs.busy.getValue()));
    }
    if (service != null && !service.message.isEmpty() && !service.message.equals(lastMessage)) {
      lastMessage = service.message;
      // Do not carry an earlier tool's message into a newly opened detail screen.
      if (!page.equals("tool_note") && !page.equals("tool_keywords") && !page.equals("tool_survey"))
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
    if (note != null) out.putString("note", note.getEditText().getText().toString());
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
