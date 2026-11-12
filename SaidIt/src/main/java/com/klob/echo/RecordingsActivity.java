package com.klob.echo;

import android.content.*;
import android.media.*;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Single in-app playback owner. No second player or capture stream. */
public final class RecordingsActivity extends AppCompatActivity {
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private ClipStore store;
  private ListView list;
  private TextView status;
  private MediaPlayer player;
  private AudioFocusRequest focusRequest;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new ClipStore(this);
    LinearLayout body = Ui.screen(this);
    Ui.text(this, body, "Recordings", 28);
    status = Ui.text(this, body, "", 14);
    Ui.button(this, body, "STOP PLAYBACK", this::stopPlayer);
    Ui.button(this, body, "BACK", this::finish);
    list = new ListView(this);
    body.addView(
        list,
        new LinearLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels * 2 / 3));
    refresh();
  }

  private void refresh() {
    files.execute(
        () -> {
          List<File> recordings = store.list();
          recordings.addAll(new TraceStore(this).list());
          recordings.sort(Comparator.comparingLong(File::lastModified).reversed());
          runOnUiThread(() -> render(recordings));
        });
  }

  private void render(List<File> recordings) {
    if (isFinishing() || isDestroyed()) return;
    status.setText(
        recordings.isEmpty()
            ? "No saved recordings or traces."
            : "Latest first // " + recordings.size() + " items");
    List<String> labels = new ArrayList<>();
    for (File file : recordings)
      labels.add(
          file.getName()
              + "\n"
              + file.getParentFile().getName()
              + " // "
              + file.length() / 1024
              + " KB");
    list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          File file = recordings.get(position);
          new androidx.appcompat.app.AlertDialog.Builder(this)
              .setTitle(file.getName())
              .setItems(
                  new String[] {"Open / play", "Share", "Rename", "Delete"},
                  (dialog, action) -> {
                    if (action == 0) {
                      if (ClipStore.isAudio(file.getName())) play(file);
                      else openTrace(file);
                    }
                    if (action == 1) share(file);
                    if (action == 2) rename(file);
                    if (action == 3)
                      new androidx.appcompat.app.AlertDialog.Builder(this)
                          .setMessage("Delete " + file.getName() + "?")
                          .setNegativeButton("CANCEL", null)
                          .setPositiveButton(
                              "DELETE",
                              (d, w) -> {
                                stopPlayer();
                                files.execute(
                                    () -> {
                                      boolean ok = file.delete();
                                      if (ok) new File(file.getPath() + ".json").delete();
                                      runOnUiThread(
                                          () -> {
                                            if (!ok) status.setText("Could not delete item.");
                                            refresh();
                                          });
                                    });
                              })
                          .show();
                  })
              .show();
        });
    if (getIntent().getBooleanExtra("play_last", false)) {
      getIntent().removeExtra("play_last");
      for (File file : recordings)
        if (ClipStore.isAudio(file.getName())) {
          play(file);
          break;
        }
    }
  }

  private void openTrace(File file) {
    if (file.getName().endsWith(".txt") || file.getName().endsWith(".json")) {
      files.execute(
          () -> {
            try {
              String text = Io.read(file.toPath());
              runOnUiThread(
                  () ->
                      new androidx.appcompat.app.AlertDialog.Builder(this)
                          .setTitle(file.getName())
                          .setMessage(text.length() > 65536 ? text.substring(0, 65536) : text)
                          .setPositiveButton("CLOSE", null)
                          .show());
            } catch (IOException e) {
              runOnUiThread(() -> status.setText("Could not read trace."));
            }
          });
    } else
      try {
        android.net.Uri uri =
            FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file);
        startActivity(
            new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, ClipStore.mime(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
      } catch (RuntimeException e) {
        status.setText("No app could open this trace. Use SHARE to export it.");
      }
  }

  private void play(File file) {
    stopPlayer();
    try {
      player = new MediaPlayer();
      final MediaPlayer current = player;
      AudioAttributes attributes =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build();
      player.setAudioAttributes(attributes);
      focusRequest =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
              .setAudioAttributes(attributes)
              .setOnAudioFocusChangeListener(
                  change -> {
                    if (change < 0 && player == current) stopPlayer();
                  })
              .build();
      if (getSystemService(AudioManager.class).requestAudioFocus(focusRequest)
          != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
        stopPlayer();
        status.setText("Audio focus unavailable.");
        return;
      }
      player.setDataSource(file.getPath());
      player.setOnPreparedListener(
          p -> {
            if (p != player || isFinishing() || isDestroyed()) return;
            try {
              p.start();
              status.setText("Playing // " + file.getName());
            } catch (IllegalStateException e) {
              stopPlayer();
              status.setText("Playback stopped.");
            }
          });
      player.setOnCompletionListener(
          p -> {
            if (p == player) stopPlayer();
          });
      player.setOnErrorListener(
          (p, w, e) -> {
            if (p == player) {
              stopPlayer();
              status.setText("Playback failed.");
            }
            return true;
          });
      player.prepareAsync();
    } catch (IOException | RuntimeException e) {
      stopPlayer();
      status.setText("Could not play this recording.");
    }
  }

  private void share(File file) {
    try {
      android.net.Uri uri =
          FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file);
      Intent share =
          new Intent(Intent.ACTION_SEND)
              .setType(ClipStore.mime(file))
              .putExtra(Intent.EXTRA_STREAM, uri)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      share.setClipData(ClipData.newRawUri("Recording", uri));
      startActivity(Intent.createChooser(share, "Share recording"));
    } catch (RuntimeException e) {
      status.setText("No app could share this recording.");
    }
  }

  private void rename(File file) {
    EditText name = new EditText(this);
    String base = file.getName().substring(0, file.getName().lastIndexOf('.'));
    name.setText(base);
    name.setSelectAllOnFocus(true);
    new androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Rename")
        .setView(name)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton(
            "RENAME",
            (d, w) -> {
              String next = name.getText().toString().trim();
              if (next.isEmpty()
                  || next.length() > 120
                  || next.contains("/")
                  || next.contains("\\")
                  || next.equals(".")
                  || next.equals("..")) {
                status.setText("Choose a shorter name without path separators.");
                return;
              }
              File target =
                  new File(
                      file.getParentFile(),
                      next + file.getName().substring(file.getName().lastIndexOf('.')));
              stopPlayer();
              files.execute(
                  () -> {
                    try {
                      Files.move(file.toPath(), target.toPath());
                      File metadata = new File(file.getPath() + ".json");
                      if (metadata.exists())
                        Files.move(
                            metadata.toPath(), new File(target.getPath() + ".json").toPath());
                    } catch (IOException e) {
                      runOnUiThread(
                          () -> status.setText("Rename failed; that name may already exist."));
                    }
                    refresh();
                  });
            })
        .show();
  }

  private void stopPlayer() {
    if (player != null) {
      MediaPlayer previous = player;
      player = null;
      previous.release();
    }
    if (focusRequest != null) {
      getSystemService(AudioManager.class).abandonAudioFocusRequest(focusRequest);
      focusRequest = null;
    }
  }

  @Override
  protected void onStop() {
    stopPlayer();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    files.shutdown();
    super.onDestroy();
  }
}
