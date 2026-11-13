package com.klob.echo.ui;

import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.*;
import com.google.android.material.chip.*;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.*;
import com.klob.echo.storage.ClipStore;
import java.io.File;
import java.util.*;

public final class LibraryFragment extends Fragment {
  private LibraryModel model;
  private Adapter adapter;
  private TextView empty;
  private int filter;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    model = new ViewModelProvider(requireActivity()).get(LibraryModel.class);
    Integer stored = model.state.get("filter");
    filter = stored == null ? 0 : stored;
    getParentFragmentManager()
        .setFragmentResultListener(
            "confirmed",
            this,
            (key, result) -> {
              String action = result.getString("action", "");
              if (action.startsWith("delete:")) model.delete(new File(action.substring(7)));
            });
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
    LinearLayout body = Ui.column(requireContext());
    body.setPadding(Ui.dp(requireContext(), 20), 0, Ui.dp(requireContext(), 20), 0);
    LinearLayout controls = new LinearLayout(requireContext());
    controls.setGravity(Gravity.CENTER_VERTICAL);
    body.addView(controls);
    HorizontalScrollView scroller = new HorizontalScrollView(requireContext());
    scroller.setHorizontalScrollBarEnabled(false);
    controls.addView(scroller, new LinearLayout.LayoutParams(0, -2, 1));
    ChipGroup chips = new ChipGroup(requireContext());
    chips.setSingleLine(true);
    chips.setSingleSelection(true);
    chips.setSelectionRequired(true);
    scroller.addView(chips);
    int[] titles = {
      R.string.filter_all, R.string.filter_audio, R.string.filter_notes, R.string.filter_captures
    };
    for (int j = 0; j < titles.length; j++) {
      int index = j;
      Chip chip = new Chip(requireContext());
      chip.setText(titles[j]);
      chip.setCheckable(true);
      chip.setChecked(filter == j);
      chip.setEnsureMinTouchTargetSize(true);
      chips.addView(chip);
      chip.setOnClickListener(
          v -> {
            filter = index;
            model.state.set("filter", filter);
            show(model.items.getValue());
          });
    }
    ImageButton refresh = icon(R.drawable.ic_refresh, getString(R.string.refresh));
    controls.addView(
        refresh,
        new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
    refresh.setOnClickListener(v -> model.refresh());
    empty = Ui.text(body, R.string.loading, 18, true);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(0, Ui.dp(requireContext(), 64), 0, Ui.dp(requireContext(), 32));
    RecyclerView list = new RecyclerView(requireContext());
    list.setId(R.id.library_list);
    list.setLayoutManager(new LinearLayoutManager(requireContext()));
    adapter = new Adapter();
    list.setAdapter(adapter);
    body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    return body;
  }

  @Override
  public void onViewCreated(View view, Bundle state) {
    model.items.observe(getViewLifecycleOwner(), this::show);
    model.feedback.observe(
        getViewLifecycleOwner(),
        res -> {
          if (res != 0) {
            Snackbar.make(view, res, Snackbar.LENGTH_LONG).show();
            model.feedback.setValue(0);
          }
        });
  }

  @Override
  public void onResume() {
    super.onResume();
    model.refresh();
  }

  private void show(List<LibraryModel.Item> all) {
    if (adapter == null || all == null) return;
    List<LibraryModel.Item> rows = new ArrayList<>();
    for (LibraryModel.Item item : all) if (filter == 0 || filter == item.kind) rows.add(item);
    adapter.submitList(rows);
    empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    empty.setText(
        all.isEmpty()
            ? getString(R.string.library_empty) + "\n\n" + getString(R.string.library_empty_help)
            : getString(R.string.library_no_matches));
  }

  private ImageButton icon(int drawable, String label) {
    ImageButton b = new ImageButton(requireContext());
    b.setImageResource(drawable);
    b.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            Ui.color(requireContext(), com.google.android.material.R.attr.colorOnSurfaceVariant)));
    b.setContentDescription(label);
    android.util.TypedValue value = new android.util.TypedValue();
    requireContext()
        .getTheme()
        .resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
    b.setBackgroundResource(value.resourceId);
    return b;
  }

  private void open(LibraryModel.Item item) {
    if (item.audio) {
      PlaybackSheet.forFile(item.file, item.title).show(getParentFragmentManager(), "playback");
      return;
    }
    String name = item.file.getName();
    if (name.endsWith(".txt") || name.endsWith(".json")) {
      TextSheet.show(getParentFragmentManager(), item.title, item.file.getPath(), false);
      return;
    }
    try {
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri(item.file), ClipStore.mime(item.file))
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (RuntimeException e) {
      Snackbar.make(requireView(), R.string.open_failed, Snackbar.LENGTH_LONG).show();
    }
  }

  private Uri uri(File file) {
    return FileProvider.getUriForFile(
        requireContext(), BuildConfig.APPLICATION_ID + ".provider", file);
  }

  private void share(File file) {
    try {
      Uri uri = uri(file);
      Intent intent =
          new Intent(Intent.ACTION_SEND)
              .setType(ClipStore.mime(file))
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      intent.setClipData(ClipData.newRawUri(getString(R.string.recording_name), uri));
      startActivity(Intent.createChooser(intent, getString(R.string.share)));
    } catch (RuntimeException e) {
      Snackbar.make(requireView(), R.string.share_failed, Snackbar.LENGTH_LONG).show();
    }
  }

  private void options(View anchor, LibraryModel.Item item) {
    PopupMenu menu = new PopupMenu(requireContext(), anchor);
    menu.getMenu().add(0, 1, 0, R.string.share);
    menu.getMenu().add(0, 2, 1, R.string.rename);
    menu.getMenu().add(0, 3, 2, R.string.delete);
    menu.setOnMenuItemClickListener(
        action -> {
          if (action.getItemId() == 1) share(item.file);
          if (action.getItemId() == 2)
            RenameDialog.forFile(item.file).show(getParentFragmentManager(), "rename");
          if (action.getItemId() == 3)
            ConfirmDialog.show(
                getParentFragmentManager(),
                R.string.delete_title,
                R.string.delete_help,
                R.string.delete,
                "delete:" + item.file.getPath());
          return true;
        });
    menu.show();
  }

  private final class Adapter
      extends androidx.recyclerview.widget.ListAdapter<LibraryModel.Item, Holder> {
    Adapter() {
      super(
          new DiffUtil.ItemCallback<LibraryModel.Item>() {
            public boolean areItemsTheSame(LibraryModel.Item a, LibraryModel.Item b) {
              return a.file.equals(b.file);
            }

            public boolean areContentsTheSame(LibraryModel.Item a, LibraryModel.Item b) {
              return a.title.equals(b.title) && a.detail.equals(b.detail);
            }
          });
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout row = new LinearLayout(requireContext());
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, Ui.dp(requireContext(), 12), 0, Ui.dp(requireContext(), 12));
      row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
      ImageButton open = icon(R.drawable.ic_play, getString(R.string.play));
      row.addView(
          open,
          new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
      LinearLayout labels = Ui.column(requireContext());
      row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      TextView title = Ui.text(labels, "", 17, false), detail = Ui.text(labels, "", 14, true);
      ImageButton more = icon(R.drawable.ic_more, getString(R.string.more_options));
      row.addView(
          more,
          new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
      return new Holder(row, title, detail, more, open);
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
      LibraryModel.Item item = getItem(position);
      h.open.setImageResource(item.audio ? R.drawable.ic_play : R.drawable.ic_library);
      h.open.setContentDescription(
          getString(item.audio ? R.string.play : R.string.open) + ": " + item.title);
      h.open.setOnClickListener(v -> open(item));
      h.title.setText(item.title);
      h.detail.setText(item.detail);
      h.itemView.setOnClickListener(v -> open(item));
      h.itemView.setFocusable(true);
      h.itemView.setContentDescription(
          item.title
              + ". "
              + item.detail
              + ". "
              + getString(item.audio ? R.string.play : R.string.open));
      h.more.setContentDescription(getString(R.string.more_options) + ": " + item.title);
      h.more.setOnClickListener(v -> options(v, item));
    }
  }

  private static final class Holder extends RecyclerView.ViewHolder {
    final TextView title, detail;
    final ImageButton more, open;

    Holder(View row, TextView title, TextView detail, ImageButton more, ImageButton open) {
      super(row);
      this.title = title;
      this.detail = detail;
      this.more = more;
      this.open = open;
    }
  }
}
