package com.klob.echo.ui;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.klob.echo.R;

public final class ConfirmDialog extends DialogFragment {
  static void show(FragmentManager manager, int title, int message, int positive, String action) {
    ConfirmDialog d = new ConfirmDialog();
    Bundle b = new Bundle();
    b.putInt("title", title);
    b.putInt("message", message);
    b.putInt("positive", positive);
    b.putString("action", action);
    d.setArguments(b);
    d.show(manager, "confirm");
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    Bundle b = requireArguments();
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(b.getInt("title"))
        .setMessage(b.getInt("message"))
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(
            b.getInt("positive"),
            (d, w) -> getParentFragmentManager().setFragmentResult("confirmed", b))
        .create();
  }
}
