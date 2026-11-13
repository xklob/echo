package com.klob.echo.ui;

import android.app.Dialog;
import android.graphics.*;
import android.os.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.klob.echo.R;
import java.io.File;
import java.util.concurrent.*;

public final class ImageSheet extends DialogFragment {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private Bitmap bitmap;
  private boolean disposed;

  static void show(FragmentManager manager, String title, File file) {
    ImageSheet d = new ImageSheet();
    Bundle args = new Bundle();
    args.putString("title", title);
    args.putString("path", file.getPath());
    d.setArguments(args);
    d.show(manager, "image");
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle state) {
    LinearLayout body = Ui.column(requireContext());
    int pad = Ui.dp(requireContext(), 16);
    body.setPadding(pad, 0, pad, 0);
    TextView status = Ui.text(body, R.string.loading, 14, true);
    ImageView image = new ImageView(requireContext());
    image.setAdjustViewBounds(true);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription(getString(R.string.image_description));
    body.addView(
        image,
        new LinearLayout.LayoutParams(
            -1,
            Ui.dp(
                requireContext(),
                Math.min(
                    520,
                    requireContext().getResources().getConfiguration().screenHeightDp - 200))));
    Handler main = new Handler(Looper.getMainLooper());
    worker.execute(
        () -> {
          Bitmap value = null;
          try {
            String path = requireArguments().getString("path");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 1600) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            value = BitmapFactory.decodeFile(path, o);
          } catch (RuntimeException ignored) {
          }
          Bitmap result = value;
          main.post(
              () -> {
                if (disposed) {
                  if (result != null) result.recycle();
                  return;
                }
                bitmap = result;
                if (result == null) status.setText(R.string.image_unavailable);
                else {
                  status.setVisibility(android.view.View.GONE);
                  image.setImageBitmap(result);
                }
              });
        });
    return new MaterialAlertDialogBuilder(requireContext())
        .setTitle(requireArguments().getString("title"))
        .setView(body)
        .setPositiveButton(R.string.close, null)
        .create();
  }

  @Override
  public void onDestroy() {
    disposed = true;
    worker.shutdown();
    if (bitmap != null) bitmap.recycle();
    super.onDestroy();
  }
}
