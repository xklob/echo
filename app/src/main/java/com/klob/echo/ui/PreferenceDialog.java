package com.klob.echo.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.klob.echo.R;
import com.klob.echo.recording.CaptureTools;
import com.klob.echo.settings.AppSettings;

/** A retained dialog description with explicit validation and no global Apply form. */
public final class PreferenceDialog extends DialogFragment {
  private TextInputLayout input;

  private static PreferenceDialog create(String key, int title, int help, boolean pending) {
    PreferenceDialog d = new PreferenceDialog();
    Bundle a = new Bundle();
    a.putString("key", key);
    a.putInt("title", title);
    a.putInt("help", help);
    a.putBoolean("pending", pending);
    d.setArguments(a);
    return d;
  }

  public static PreferenceDialog number(
      String key, int title, int help, int fallback, int min, int max, boolean pending) {
    PreferenceDialog d = create(key, title, help, pending);
    Bundle a = d.requireArguments();
    a.putString("type", "number");
    a.putInt("default", fallback);
    a.putInt("min", min);
    a.putInt("max", max);
    return d;
  }

  public static PreferenceDialog choice(
      String key,
      int title,
      int help,
      String[] labels,
      String[] values,
      String fallback,
      boolean numeric,
      boolean pending) {
    PreferenceDialog d = create(key, title, help, pending);
    Bundle a = d.requireArguments();
    a.putString("type", "choice");
    a.putStringArray("labels", labels);
    a.putStringArray("values", values);
    a.putString("fallback", fallback);
    a.putBoolean("numeric", numeric);
    return d;
  }

  public static PreferenceDialog keywords() {
    PreferenceDialog d =
        create("keywords", R.string.keyword_words, R.string.keyword_words_help, false);
    d.requireArguments().putString("type", "words");
    return d;
  }

  public static PreferenceDialog theme() {
    PreferenceDialog d = create("theme", R.string.appearance, R.string.theme_help, false);
    Bundle a = d.requireArguments();
    a.putString("type", "theme");
    return d;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    Bundle a = requireArguments();
    String key = a.getString("key"), type = a.getString("type");
    android.content.SharedPreferences p = AppSettings.prefs(requireContext());
    MaterialAlertDialogBuilder builder =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(a.getInt("title"))
            .setNegativeButton(R.string.cancel, null);
    if (type.equals("choice") || type.equals("theme")) {
      String[] labels =
          type.equals("theme")
              ? new String[] {
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark)
              }
              : a.getStringArray("labels");
      String[] values =
          type.equals("theme")
              ? new String[] {"system", "light", "dark"}
              : a.getStringArray("values");
      String current =
          a.getBoolean("numeric")
              ? Integer.toString(p.getInt(key, Integer.parseInt(a.getString("fallback"))))
              : p.getString(key, a.getString("fallback", "system"));
      int selected = java.util.Arrays.asList(values).indexOf(current);
      builder.setSingleChoiceItems(
          labels,
          selected,
          (dialog, which) -> {
            save(a.getBoolean("numeric") ? Integer.valueOf(values[which]) : values[which]);
            dismiss();
          });
    } else {
      LinearLayout body = Ui.column(requireContext());
      int pad = Ui.dp(requireContext(), 24);
      body.setPadding(pad, 0, pad, 0);
      String initial =
          state != null
              ? state.getString("input", "")
              : type.equals("number")
                  ? Integer.toString(p.getInt(key, a.getInt("default")))
                  : p.getString(key, "");
      input =
          Ui.input(
              body,
              a.getInt("title"),
              initial,
              type.equals("number")
                  ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                  : InputType.TYPE_CLASS_TEXT);
      input.setId(R.id.input_container);
      input.getEditText().setId(R.id.input_value);
      input.getEditText().setSelectAllOnFocus(true);
      input.getEditText().setSingleLine(true);
      if (a.getInt("help") != 0) input.setHelperText(getString(a.getInt("help")));
      builder.setView(body).setPositiveButton(R.string.save, null);
    }
    return builder.create();
  }

  @Override
  public void onStart() {
    super.onStart();
    if (input == null) return;
    AlertDialog dialog = (AlertDialog) requireDialog();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              Bundle a = requireArguments();
              String text = input.getEditText().getText().toString().trim();
              Object value;
              if (a.getString("type").equals("number")) {
                try {
                  int n = Integer.parseInt(text);
                  if (n < a.getInt("min") || n > a.getInt("max")) throw new NumberFormatException();
                  value = n;
                } catch (NumberFormatException e) {
                  input.setError(getString(R.string.range_error, a.getInt("min"), a.getInt("max")));
                  input.getEditText().requestFocus();
                  return;
                }
              } else {
                if (!CaptureTools.validKeywords(text)) {
                  input.setError(getString(R.string.keyword_invalid));
                  return;
                }
                value = text;
              }
              save(value);
              dismiss();
            });
  }

  private void save(Object value) {
    Bundle a = requireArguments();
    AppSettings.save(requireContext(), a.getString("key"), value, a.getBoolean("pending"));
    Bundle result = new Bundle();
    result.putString("key", a.getString("key"));
    getParentFragmentManager().setFragmentResult("preference_saved", result);
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle out) {
    if (input != null) out.putString("input", input.getEditText().getText().toString());
    super.onSaveInstanceState(out);
  }
}
