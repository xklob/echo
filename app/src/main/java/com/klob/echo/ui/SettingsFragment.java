package com.klob.echo.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.LinearLayout;
import androidx.fragment.app.Fragment;
import com.klob.echo.R;
import com.klob.echo.settings.AppSettings;

public final class SettingsFragment extends Fragment {
  @Override
  public View onCreateView(LayoutInflater i, ViewGroup c, Bundle s) {
    LinearLayout body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    Ui.row(
        body,
        R.string.history_quality,
        R.string.history_quality_help,
        () -> open("history", R.string.history_quality));
    Ui.row(
        body,
        R.string.auto_saves,
        R.string.auto_saves_help,
        () -> open("automatic", R.string.auto_saves));
    Ui.row(
        body,
        R.string.speech_settings,
        R.string.speech_settings_help,
        () -> open("tool_speech", R.string.speech_settings));
    Ui.row(
        body,
        R.string.recording_rules,
        R.string.recording_rules_help,
        () -> open("rules", R.string.recording_rules));
    Ui.row(body, R.string.storage, R.string.storage_help, () -> open("storage", R.string.storage));
    Ui.row(
        body,
        R.string.automation,
        R.string.automation_help,
        () -> open("automation", R.string.automation));
    String theme = AppSettings.prefs(requireContext()).getString("theme", "system");
    Ui.row(
        body,
        R.string.appearance,
        getString(
            theme.equals("dark")
                ? R.string.theme_dark
                : theme.equals("light") ? R.string.theme_light : R.string.theme_system),
        () -> PreferenceDialog.theme().show(getParentFragmentManager(), "theme"));
    Ui.row(body, R.string.about, R.string.about_help, () -> open("about", R.string.about));
    return root;
  }

  private void open(String page, int title) {
    DetailActivity.open(requireContext(), page, title);
  }
}
