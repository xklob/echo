package com.klob.echo.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.*;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.R;
import java.io.File;
import java.util.*;

/** Audio clips are the primary library. Raw captures have a separate secondary destination. */
public final class LibraryFragment extends Fragment {
  private LibraryModel model;
  private Adapter adapter;
  private TextView empty, count;
  private boolean extras;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    extras = getArguments() != null && getArguments().getBoolean("extras", false);
    model = new ViewModelProvider(requireActivity()).get(LibraryModel.class);
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
    body.setPadding(
        Ui.dp(requireContext(), 20), Ui.dp(requireContext(), 8), Ui.dp(requireContext(), 20), 0);
    Ui.text(body, extras ? R.string.extras_intro : R.string.library_intro, 14, true);
    LinearLayout controls = new LinearLayout(requireContext());
    controls.setGravity(Gravity.CENTER_VERTICAL);
    body.addView(controls);
    count = new TextView(requireContext());
    count.setTextSize(14);
    controls.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton refresh =
        FileActions.icon(requireContext(), R.drawable.ic_refresh, getString(R.string.refresh));
    controls.addView(
        refresh,
        new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
    refresh.setOnClickListener(v -> model.refresh());
    if (!extras) {
      ImageButton more =
          FileActions.icon(requireContext(), R.drawable.ic_more, getString(R.string.more_options));
      controls.addView(
          more,
          new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
      more.setOnClickListener(
          v -> {
            PopupMenu menu = new PopupMenu(requireContext(), more);
            menu.getMenu().add(R.string.saved_extras);
            menu.setOnMenuItemClickListener(
                a -> {
                  DetailActivity.open(requireContext(), "extras", R.string.saved_extras);
                  return true;
                });
            menu.show();
          });
    }
    empty = Ui.text(body, R.string.loading, 18, true);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(0, Ui.dp(requireContext(), 48), 0, Ui.dp(requireContext(), 24));
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
    (extras ? model.extras : model.items).observe(getViewLifecycleOwner(), this::show);
    model.loaded.observe(
        getViewLifecycleOwner(), loaded -> show((extras ? model.extras : model.items).getValue()));
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

  private void show(List<LibraryModel.Item> rows) {
    if (adapter == null || rows == null) return;
    adapter.submitList(rows);
    count.setText(
        extras
            ? getResources().getQuantityString(R.plurals.related_items, rows.size(), rows.size())
            : getResources()
                .getQuantityString(R.plurals.recording_count, rows.size(), rows.size()));
    empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    empty.setText(
        !Boolean.TRUE.equals(model.loaded.getValue())
            ? getString(R.string.loading)
            : extras
                ? getString(R.string.extras_empty)
                : getString(R.string.library_empty)
                    + "\n\n"
                    + getString(R.string.library_empty_help));
  }

  private void open(LibraryModel.Item item, boolean play) {
    if (item.audio) DetailActivity.openClip(requireContext(), item.file.getPath(), play);
    else FileActions.open(this, item.file, item.title);
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
              return a.title.equals(b.title)
                  && a.detail.equals(b.detail)
                  && a.contextText.equals(b.contextText)
                  && a.preview.equals(b.preview);
            }
          });
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout row = Ui.column(requireContext());
      row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
      LinearLayout cardBody = Ui.card(row);
      MaterialCardView card = (MaterialCardView) cardBody.getParent();
      card.setClickable(true);
      card.setFocusable(true);
      LinearLayout header = new LinearLayout(requireContext());
      header.setGravity(Gravity.CENTER_VERTICAL);
      cardBody.addView(header);
      TextView title = new TextView(requireContext());
      title.setTextSize(20);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setTextColor(
          Ui.color(requireContext(), com.google.android.material.R.attr.colorOnSurface));
      header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      ImageButton play =
          FileActions.icon(requireContext(), R.drawable.ic_play, getString(R.string.play));
      header.addView(
          play,
          new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
      ImageButton more =
          FileActions.icon(requireContext(), R.drawable.ic_more, getString(R.string.more_options));
      header.addView(
          more,
          new LinearLayout.LayoutParams(Ui.dp(requireContext(), 48), Ui.dp(requireContext(), 48)));
      TextView detail = Ui.text(cardBody, "", 14, true),
          context = Ui.text(cardBody, "", 14, false),
          preview = Ui.text(cardBody, "", 14, true);
      preview.setMaxLines(2);
      preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
      return new Holder(row, card, title, detail, context, preview, play, more);
    }

    @Override
    public void onBindViewHolder(Holder h, int position) {
      LibraryModel.Item item = getItem(position);
      h.title.setText(item.title);
      h.detail.setText(item.detail);
      h.context.setText(item.contextText);
      h.preview.setText(item.preview);
      h.context.setVisibility(item.contextText.isEmpty() ? View.GONE : View.VISIBLE);
      h.preview.setVisibility(item.preview.isEmpty() ? View.GONE : View.VISIBLE);
      h.play.setImageResource(item.audio ? R.drawable.ic_play : R.drawable.ic_library);
      h.play.setContentDescription(
          getString(item.audio ? R.string.play : R.string.open) + ": " + item.title);
      h.play.setOnClickListener(v -> open(item, true));
      h.card.setOnClickListener(v -> open(item, false));
      h.more.setContentDescription(getString(R.string.more_options) + ": " + item.title);
      h.more.setOnClickListener(v -> FileActions.options(LibraryFragment.this, h.more, item.file));
    }
  }

  private static final class Holder extends RecyclerView.ViewHolder {
    final MaterialCardView card;
    final TextView title, detail, context, preview;
    final ImageButton play, more;

    Holder(
        View row,
        MaterialCardView card,
        TextView title,
        TextView detail,
        TextView context,
        TextView preview,
        ImageButton play,
        ImageButton more) {
      super(row);
      this.card = card;
      this.title = title;
      this.detail = detail;
      this.context = context;
      this.preview = preview;
      this.play = play;
      this.more = more;
    }
  }
}
