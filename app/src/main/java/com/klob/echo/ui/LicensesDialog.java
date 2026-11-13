package com.klob.echo.ui;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.klob.echo.R;

public final class LicensesDialog extends DialogFragment {
  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    String[] files;
    try {
      files = requireContext().getAssets().list("licenses");
    } catch (java.io.IOException e) {
      files = new String[0];
    }
    final String[] names = files;
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.licenses)
        .setItems(
            names,
            (dialog, which) ->
                TextSheet.show(
                    getParentFragmentManager(), names[which], "licenses/" + names[which], true))
        .setNegativeButton(R.string.close, null)
        .create();
  }
}
