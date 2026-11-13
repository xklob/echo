package com.klob.echo.ui;

import android.content.*;
import android.net.Uri;
import android.view.View;
import android.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.*;
import com.klob.echo.storage.ClipStore;
import java.io.File;

final class FileActions {
  static Uri uri(Context context, File file) {
    return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file);
  }

  static void share(Fragment owner, File file) {
    try {
      Uri uri = uri(owner.requireContext(), file);
      Intent intent =
          new Intent(Intent.ACTION_SEND)
              .setType(ClipStore.mime(file))
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri(owner.getString(R.string.recording_name), uri));
      owner.startActivity(Intent.createChooser(intent, owner.getString(R.string.share)));
    } catch (RuntimeException e) {
      Snackbar.make(owner.requireView(), R.string.share_failed, Snackbar.LENGTH_LONG).show();
    }
  }

  static void open(Fragment owner, File file, String title) {
    String name = file.getName();
    if (name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".gpx")) {
      TextSheet.show(owner.getParentFragmentManager(), title, file.getPath(), false);
    } else if (name.endsWith(".jpg")) {
      ImageSheet.show(owner.getParentFragmentManager(), title, file);
    } else
      try {
        owner.startActivity(
            new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(owner.requireContext(), file), ClipStore.mime(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
      } catch (RuntimeException e) {
        Snackbar.make(owner.requireView(), R.string.open_failed, Snackbar.LENGTH_LONG).show();
      }
  }

  static void options(Fragment owner, View anchor, File file) {
    PopupMenu menu = new PopupMenu(owner.requireContext(), anchor);
    menu.getMenu().add(0, 1, 0, R.string.share);
    menu.getMenu().add(0, 2, 1, R.string.rename);
    menu.getMenu().add(0, 3, 2, R.string.delete);
    menu.setOnMenuItemClickListener(
        action -> {
          if (action.getItemId() == 1) share(owner, file);
          if (action.getItemId() == 2)
            RenameDialog.forFile(file).show(owner.getParentFragmentManager(), "rename");
          if (action.getItemId() == 3) {
            boolean audio = ClipStore.isAudio(file.getName());
            ConfirmDialog.show(
                owner.getParentFragmentManager(),
                audio ? R.string.clip_delete_title : R.string.delete_title,
                audio ? R.string.clip_delete_help : R.string.delete_help,
                R.string.delete,
                "delete:" + file.getPath());
          }
          return true;
        });
    menu.show();
  }

  static android.widget.ImageButton icon(Context context, int drawable, String label) {
    android.widget.ImageButton b = new android.widget.ImageButton(context);
    b.setImageResource(drawable);
    b.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            Ui.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant)));
    b.setContentDescription(label);
    android.util.TypedValue value = new android.util.TypedValue();
    context
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
    b.setBackgroundResource(value.resourceId);
    return b;
  }
}
