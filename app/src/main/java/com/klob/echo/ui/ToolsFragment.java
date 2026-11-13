package com.klob.echo.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.klob.echo.R;
import com.klob.echo.settings.AppSettings;

public final class ToolsFragment extends ServiceFragment {
  private TextView locationState;
  private MaterialSwitch location;
  private final ActivityResultLauncher<String[]> permission =
      registerForActivityResult(
          new ActivityResultContracts.RequestMultiplePermissions(),
          result -> {
            if (service != null) service.tools.restoreConfigured();
            if (!hasLocation()) message(R.string.permission_help);
          });

  @Override
  public View onCreateView(LayoutInflater i, ViewGroup c, Bundle state) {
    LinearLayout body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    Ui.text(body, R.string.tools_intro, 16, true);
    LinearLayout card = Ui.card(body);
    location =
        Ui.toggle(
            card,
            R.string.location,
            R.string.location_help,
            prefs().getBoolean("gps_enabled", false),
            on -> {
              if (service != null) service.tools.setEnabled("gps", on, "");
              else AppSettings.save(requireContext(), "gps_enabled", on, false);
              if (on && !hasLocation())
                permission.launch(
                    new String[] {
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    });
            });
    location.setId(R.id.location_enabled);
    locationState = Ui.text(card, "", 14, true);
    Ui.row(
        body,
        R.string.keywords,
        R.string.keywords_help,
        () -> open("tool_keywords", R.string.keywords));
    Ui.row(body, R.string.camera, R.string.camera_help, () -> open("tool_camera", R.string.camera));
    Ui.row(body, R.string.screen, R.string.screen_help, () -> open("tool_screen", R.string.screen));
    Ui.row(
        body,
        R.string.audio_source,
        R.string.audio_source_help,
        () -> open("tool_source", R.string.audio_source));
    Ui.heading(body, R.string.notes_surveys);
    Ui.row(
        body,
        R.string.new_note,
        R.string.new_note_help,
        () -> open("tool_note", R.string.new_note));
    Ui.row(body, R.string.survey, R.string.survey_help, () -> open("tool_survey", R.string.survey));
    Ui.row(body, R.string.backup, R.string.backup_help, () -> open("tool_sync", R.string.backup));
    return root;
  }

  private boolean hasLocation() {
    return requireContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void open(String page, int title) {
    DetailActivity.open(requireContext(), page, title);
  }

  @Override
  protected void render() {
    if (locationState == null) return;
    boolean enabled = prefs().getBoolean("gps_enabled", false);
    Ui.setText(
        locationState,
        getString(
            !enabled
                ? R.string.off
                : !hasLocation()
                    ? R.string.permission_needed
                    : service != null && service.tools.gps
                        ? R.string.active
                        : R.string.enabled_waiting));
  }
}
