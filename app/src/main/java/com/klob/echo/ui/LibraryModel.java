package com.klob.echo.ui;

import android.app.Application;
import androidx.lifecycle.*;
import com.klob.echo.R;
import com.klob.echo.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public final class LibraryModel extends AndroidViewModel {
  static final class Item {
    final File file;
    final String title, detail;
    final int kind;
    final boolean audio;

    Item(Application app, File file) {
      this.file = file;
      String name = file.getName();
      audio = ClipStore.isAudio(name);
      int label =
          audio
              ? R.string.recording_name
              : name.endsWith(".txt")
                  ? R.string.note_name
                  : name.endsWith(".json")
                      ? R.string.survey_name
                      : name.endsWith(".gpx")
                          ? R.string.location_name
                          : name.endsWith(".mp4") ? R.string.video_name : R.string.screenshot_name;
      kind = audio ? 1 : name.endsWith(".txt") || name.endsWith(".json") ? 2 : 3;
      String base = name.substring(0, Math.max(0, name.lastIndexOf('.')));
      boolean generated = base.startsWith("Echo-20") || base.matches("20\\d\\d-.*");
      String display = generated ? app.getString(label) : base;
      if (generated && audio) {
        String category = file.getParentFile().getName();
        display =
            app.getString(
                category.equals("auto")
                    ? R.string.category_auto
                    : category.equals("continuous")
                        ? R.string.category_continuous
                        : category.equals("activity")
                            ? R.string.category_activity
                            : R.string.category_manual);
      }
      if (generated && name.endsWith(".txt"))
        try {
          String first = Io.read(file.toPath()).strip().split("\\R", 2)[0];
          if (!first.isEmpty())
            display = first.length() > 80 ? first.substring(0, 80) + "…" : first;
        } catch (IOException ignored) {
        }
      title = display;
      long timestamp = file.lastModified(), samples = 0;
      if (audio)
        try {
          JSONObject meta = new JSONObject(Io.read(new File(file.getPath() + ".json").toPath()));
          timestamp = meta.optLong("start_time_ms", timestamp);
          samples = meta.optLong("captured_samples", 0);
        } catch (Exception ignored) {
        }
      detail =
          java.text.DateFormat.getDateTimeInstance(
                      java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                  .format(new Date(timestamp))
              + "\n"
              + (samples > 0 ? Ui.duration(samples) + " · " : "")
              + Ui.bytes(file.length())
              + " · "
              + name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
    }
  }

  final MutableLiveData<List<Item>> items = new MutableLiveData<>(Collections.emptyList());
  final MutableLiveData<Integer> feedback = new MutableLiveData<>(0);
  final SavedStateHandle state;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  public LibraryModel(Application application, SavedStateHandle state) {
    super(application);
    this.state = state;
  }

  void refresh() {
    worker.execute(this::load);
  }

  private void load() {
    List<File> files = new ClipStore(getApplication()).list();
    files.addAll(new TraceStore(getApplication()).list());
    files.sort(Comparator.comparingLong(File::lastModified).reversed());
    List<Item> rows = new ArrayList<>();
    for (File file : files) rows.add(new Item(getApplication(), file));
    items.postValue(rows);
  }

  void delete(File file) {
    worker.execute(
        () -> {
          boolean ok = file.delete();
          if (ok) {
            File meta = new File(file.getPath() + ".json");
            if (meta.exists()) ok = meta.delete();
          }
          feedback.postValue(ok ? R.string.file_deleted : R.string.file_delete_failed);
          load();
        });
  }

  void rename(File file, String name) {
    worker.execute(
        () -> {
          File target =
              new File(
                  file.getParentFile(),
                  name + file.getName().substring(file.getName().lastIndexOf('.')));
          if (target.equals(file)) return;
          File metadata = new File(file.getPath() + ".json"),
              targetMetadata = new File(target.getPath() + ".json");
          boolean moved = false;
          try {
            if (target.exists() || targetMetadata.exists()) throw new IOException();
            Files.move(file.toPath(), target.toPath());
            moved = true;
            if (metadata.exists()) Files.move(metadata.toPath(), targetMetadata.toPath());
            feedback.postValue(R.string.saved);
          } catch (IOException e) {
            if (moved)
              try {
                Files.move(target.toPath(), file.toPath());
              } catch (IOException ignored) {
              }
            feedback.postValue(R.string.rename_failed);
          }
          load();
        });
  }

  @Override
  protected void onCleared() {
    worker.shutdown();
  }
}
