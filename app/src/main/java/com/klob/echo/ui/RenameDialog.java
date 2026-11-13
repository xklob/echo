package com.klob.echo.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.klob.echo.R;
import java.io.File;

public final class RenameDialog extends DialogFragment {
  private TextInputLayout input;

  static RenameDialog forFile(File file) {
    RenameDialog d = new RenameDialog();
    Bundle b = new Bundle();
    b.putString("path", file.getPath());
    d.setArguments(b);
    return d;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    File file = new File(requireArguments().getString("path"));
    String name = file.getName();
    name = name.substring(0, name.lastIndexOf('.'));
    LinearLayout body = Ui.column(requireContext());
    int pad = Ui.dp(requireContext(), 24);
    body.setPadding(pad, 0, pad, 0);
    input =
        Ui.input(
            body,
            R.string.file_name,
            state == null ? name : state.getString("name", name),
            InputType.TYPE_CLASS_TEXT);
    input.getEditText().setSingleLine(true);
    input.getEditText().setSelectAllOnFocus(true);
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.rename)
        .setView(body)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.rename, null)
        .create();
  }

  @Override
  public void onStart() {
    super.onStart();
    ((AlertDialog) requireDialog())
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              String name = input.getEditText().getText().toString().trim();
              if (name.isEmpty()
                  || name.length() > 120
                  || name.contains("/")
                  || name.contains("\\")
                  || name.equals(".")
                  || name.equals("..")) {
                input.setError(getString(R.string.file_name_error));
                return;
              }
              new ViewModelProvider(requireActivity())
                  .get(LibraryModel.class)
                  .rename(new File(requireArguments().getString("path")), name);
              dismiss();
            });
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle out) {
    out.putString("name", input.getEditText().getText().toString());
    super.onSaveInstanceState(out);
  }
}
