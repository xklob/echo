package com.klob.echo.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.R;
import com.klob.echo.library.*;
import java.io.File;

public final class ClipDetailFragment extends Fragment {
  private LibraryModel model;
  private LinearLayout body;
  private AudioPlayback player;
  private RecordingMap map;
  private Bundle playerState, mapState;
  private LibraryModel.Item shown;
  private String revision = "";
  private boolean started, resumed, played;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    model = new ViewModelProvider(requireActivity()).get(LibraryModel.class);
    if (state != null) {
      playerState = state.getBundle("player");
      mapState = state.getBundle("map");
      played = state.getBoolean("played");
    }
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
  public View onCreateView(LayoutInflater i, ViewGroup parent, Bundle state) {
    body = Ui.column(requireContext());
    View root = Ui.scroll(requireContext(), body);
    Ui.text(body, R.string.loading, 18, true);
    return root;
  }

  @Override
  public void onViewCreated(View view, Bundle state) {
    model.detail.observe(getViewLifecycleOwner(), this::show);
    model.feedback.observe(
        getViewLifecycleOwner(),
        res -> {
          if (res != 0) {
            Snackbar.make(view, res, Snackbar.LENGTH_LONG).show();
            model.feedback.setValue(0);
          }
        });
    MaterialToolbar toolbar = requireActivity().findViewById(R.id.toolbar);
    toolbar.getMenu().clear();
    toolbar.getMenu().add(0, 1, 0, R.string.share).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
    toolbar.getMenu().add(0, 2, 1, R.string.rename);
    toolbar.getMenu().add(0, 3, 2, R.string.delete);
    toolbar.getMenu().add(0, 4, 3, R.string.refresh);
    toolbar.setOnMenuItemClickListener(
        action -> {
          if (shown == null) return true;
          if (action.getItemId() == 1) FileActions.share(this, shown.file);
          if (action.getItemId() == 2)
            RenameDialog.forFile(shown.file).show(getParentFragmentManager(), "rename");
          if (action.getItemId() == 3)
            ConfirmDialog.show(
                getParentFragmentManager(),
                R.string.clip_delete_title,
                R.string.clip_delete_help,
                R.string.delete,
                "delete:" + shown.file.getPath());
          if (action.getItemId() == 4) {
            revision = "";
            reload();
          }
          return true;
        });
  }

  private void reload() {
    String path = model.state.get("detail_path");
    if (path == null) path = requireArguments().getString("file", "");
    model.openClip(path);
  }

  private void show(LibraryModel.Item item) {
    if (body == null) return;
    if (item == null) {
      release();
      body.removeAllViews();
      Ui.text(body, R.string.clip_missing, 18, true);
      shown = null;
      return;
    }
    RecordingContext data = item.recording;
    RecordingInfo recording = data.recording;
    String next =
        item.file.getPath()
            + item.title
            + item.detail
            + item.contextText
            + item.preview
            + data.locationCount
            + data.unreadableLocations;
    if (next.equals(revision)) return;
    revision = next;
    if (player != null) playerState = player.saveState();
    if (map != null) mapState = map.saveState();
    release();
    shown = item;
    body.removeAllViews();
    ((MaterialToolbar) requireActivity().findViewById(R.id.toolbar)).setTitle(item.title);
    Ui.text(body, LibraryText.date(recording.timestamp), 16, true);
    LinearLayout audio = Ui.card(body);
    Ui.text(
        audio,
        LibraryText.duration(recording.duration)
            + " · "
            + LibraryText.source(requireContext(), recording.source),
        20,
        false);
    player =
        new AudioPlayback(
            requireContext(),
            item.file,
            playerState,
            !played && requireArguments().getBoolean("play", false));
    played = true;
    playerState = null;
    audio.addView(player, new LinearLayout.LayoutParams(-1, -2));
    if (started) player.start();
    LinearLayout location = Ui.card(body);
    Ui.heading(location, R.string.clip_location);
    if (data.locationCount > 0) {
      Ui.text(
          location,
          getResources()
              .getQuantityString(R.plurals.gps_fixes, data.locationCount, data.locationCount),
          14,
          true);
      map = new RecordingMap(requireContext(), data, mapState);
      location.addView(map);
      mapState = null;
      if (started) map.start();
      if (resumed) map.resume();
      Ui.text(
          location,
          getString(R.string.map_first, LibraryText.coordinates(data.firstLocation)),
          15,
          false);
      Ui.text(location, LibraryText.clock(data.firstLocation.time), 13, true);
      if (data.locationCount > 1) {
        Ui.text(
            location,
            getString(R.string.map_last, LibraryText.coordinates(data.lastLocation)),
            15,
            false);
        Ui.text(location, LibraryText.clock(data.lastLocation.time), 13, true);
      }
    } else Ui.text(location, R.string.clip_no_location, 15, true);
    if (data.unreadableLocations) Ui.text(location, R.string.clip_location_partial, 14, true);
    LinearLayout related = Ui.card(body);
    related.setId(R.id.clip_context);
    Ui.heading(related, R.string.clip_context);
    Ui.text(related, R.string.clip_context_help, 14, true);
    if (data.attachments.isEmpty()) Ui.text(related, R.string.clip_context_empty, 15, true);
    for (TraceItem attachment : data.attachments) {
      String title =
          LibraryText.generated(LibraryText.base(attachment.file))
              ? LibraryText.traceLabel(requireContext(), attachment.type)
              : LibraryText.traceTitle(requireContext(), attachment);
      long offset = attachment.time - recording.timestamp;
      String time =
          offset >= 0
              ? getString(
                  R.string.clip_attachment_time,
                  LibraryText.duration(offset),
                  LibraryText.clock(attachment.time))
              : getString(R.string.clip_attachment_overlap, LibraryText.clock(attachment.time));
      Ui.row(
          related,
          title,
          time + (attachment.preview.isEmpty() ? "" : "\n" + attachment.preview),
          () -> FileActions.open(this, attachment.file, title));
    }
    LinearLayout details = Ui.card(body);
    Ui.heading(details, R.string.recording_details);
    field(details, R.string.recorded_from, LibraryText.timestamp(recording.timestamp));
    if (recording.window != null)
      field(details, R.string.recorded_until, LibraryText.timestamp(recording.window.end));
    field(details, R.string.recorded_duration, LibraryText.duration(recording.duration));
    field(details, R.string.recorded_type, LibraryText.category(requireContext(), recording.kind));
    field(
        details, R.string.recorded_source, LibraryText.source(requireContext(), recording.source));
    field(
        details,
        R.string.recorded_quality,
        recording.sampleRate > 0
            ? getString(R.string.recorded_audio_info, recording.format, recording.sampleRate)
            : recording.format);
    field(details, R.string.recorded_file, item.file.getName() + "\n" + Ui.bytes(recording.size));
    if (recording.window == null) Ui.text(details, R.string.timing_unknown, 14, true);
    Ui.button(body, R.string.share_recording, false, () -> FileActions.share(this, item.file));
  }

  private void field(LinearLayout parent, int label, String value) {
    Ui.text(parent, getString(label), 13, true);
    TextView text = Ui.text(parent, value, 16, false);
    text.setTextIsSelectable(true);
  }

  private void release() {
    if (player != null) {
      player.stop();
      player = null;
    }
    if (map != null) {
      map.destroy();
      map = null;
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    started = true;
    if (player != null) player.start();
    if (map != null) map.start();
  }

  @Override
  public void onResume() {
    super.onResume();
    resumed = true;
    if (map != null) map.resume();
    reload();
  }

  @Override
  public void onPause() {
    if (map != null) map.pause();
    resumed = false;
    super.onPause();
  }

  @Override
  public void onStop() {
    if (player != null) player.stop();
    if (map != null) map.stop();
    started = false;
    super.onStop();
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    if (player != null) out.putBundle("player", player.saveState());
    if (map != null) out.putBundle("map", map.saveState());
    out.putBoolean("played", played);
    super.onSaveInstanceState(out);
  }

  @Override
  public void onDestroyView() {
    release();
    body = null;
    revision = "";
    super.onDestroyView();
  }
}
