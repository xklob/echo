package com.klob.echo.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.klob.echo.R;
import com.klob.echo.storage.Io;
import java.io.*;

/** Small local text documents and bundled attribution, capped at 128 KiB. */
public final class TextSheet extends DialogFragment {
  public static void show(FragmentManager fm, String title, String source, boolean asset) {
    TextSheet d = new TextSheet();
    Bundle b = new Bundle();
    b.putString("title", title);
    b.putString("source", source);
    b.putBoolean("asset", asset);
    d.setArguments(b);
    d.show(fm, "text");
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    Bundle b = requireArguments();
    String text;
    try (InputStream in =
        b.getBoolean("asset")
            ? requireContext().getAssets().open(b.getString("source"))
            : new FileInputStream(b.getString("source"))) {
      text = new String(Io.readBytes(in, 131072), java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      text = getString(b.getBoolean("asset") ? R.string.license_failed : R.string.text_read_failed);
    }
    if (!b.getBoolean("asset") && b.getString("source").endsWith(".json"))
      text = com.klob.echo.tools.Survey.readable(text);
    LinearLayout body = Ui.column(requireContext());
    int pad = Ui.dp(requireContext(), 24);
    body.setPadding(pad, 0, pad, 0);
    TextView view = Ui.text(body, text, 15, false);
    view.setTextIsSelectable(true);
    ScrollView scroll = new ScrollView(requireContext());
    scroll.addView(body);
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(b.getString("title"))
        .setView(scroll)
        .setPositiveButton(R.string.close, null)
        .create();
  }
}
