package com.klob.echo.ui;

import android.app.Application;
import androidx.lifecycle.*;
import com.klob.echo.R;
import com.klob.echo.library.*;
import com.klob.echo.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class LibraryModel extends AndroidViewModel {
  static final class Item {
    final File file;
    final String title, detail, contextText, preview;
    final RecordingContext recording;
    final TraceItem trace;
    final boolean audio;

    Item(Application app, RecordingContext recording) {
      this.recording = recording;
      trace = null;
      audio = true;
      RecordingInfo info = recording.recording;
      file = info.file;
      title = LibraryText.title(app, info);
      detail =
          LibraryText.date(info.timestamp)
              + "\n"
              + LibraryText.duration(info.duration)
              + " · "
              + Ui.bytes(info.size)
              + " · "
              + info.format
              + (info.source.isEmpty() ? "" : " · " + LibraryText.source(app, info.source));
      contextText = LibraryText.context(app, recording);
      preview = LibraryText.preview(app, recording);
    }

    Item(Application app, TraceItem trace) {
      this.trace = trace;
      recording = null;
      audio = false;
      file = trace.file;
      title = LibraryText.traceTitle(app, trace);
      detail =
          LibraryText.date(trace.time)
              + " · "
              + Ui.bytes(file.length())
              + " · "
              + RecordingRepository.extension(file);
      contextText = "";
      preview = trace.preview;
    }
  }

  final MutableLiveData<List<Item>> items = new MutableLiveData<>(Collections.emptyList());
  final MutableLiveData<List<Item>> extras = new MutableLiveData<>(Collections.emptyList());
  final MutableLiveData<Item> detail = new MutableLiveData<>();
  final MutableLiveData<Boolean> loaded = new MutableLiveData<>(false);
  final MutableLiveData<Integer> feedback = new MutableLiveData<>(0);
  final SavedStateHandle state;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final RecordingRepository repository;
  private volatile String detailPath;

  public LibraryModel(Application application, SavedStateHandle state) {
    super(application);
    this.state = state;
    detailPath = state.get("detail_path");
    repository = new RecordingRepository(application);
  }

  void refresh() {
    worker.execute(this::load);
  }

  private void load() {
    List<Item> rows = new ArrayList<>();
    for (RecordingContext recording : repository.recordings())
      rows.add(new Item(getApplication(), recording));
    items.postValue(rows);
    List<Item> other = new ArrayList<>();
    for (TraceItem trace : repository.traces()) other.add(new Item(getApplication(), trace));
    extras.postValue(other);
    loaded.postValue(true);
  }

  void openClip(String path) {
    state.set("detail_path", path);
    detailPath = path;
    worker.execute(() -> loadDetail(path));
  }

  private void loadDetail(String path) {
    RecordingContext recording = repository.recording(new File(path));
    if (path.equals(detailPath))
      detail.postValue(recording == null ? null : new Item(getApplication(), recording));
  }

  void delete(File file) {
    worker.execute(
        () -> {
          boolean ok = file.delete();
          if (ok) {
            File meta = metadata(file);
            if (meta.exists()) ok = meta.delete();
          }
          feedback.postValue(ok ? R.string.file_deleted : R.string.file_delete_failed);
          load();
          String path = detailPath;
          if (path != null && path.equals(file.getPath())) loadDetail(path);
        });
  }

  private static File metadata(File file) {
    return ClipStore.isAudio(file.getName())
        ? new File(file.getPath() + ".json")
        : TraceItem.metadata(file);
  }

  void rename(File file, String name) {
    worker.execute(
        () -> {
          File target =
              new File(
                  file.getParentFile(),
                  name + file.getName().substring(file.getName().lastIndexOf('.')));
          if (target.equals(file)) return;
          File metadata = metadata(file), targetMetadata = metadata(target);
          boolean moved = false;
          try {
            if (target.exists() || targetMetadata.exists()) throw new IOException();
            if (!ClipStore.isAudio(file.getName()) && !metadata.exists()) {
              File partial = new File(metadata.getPath() + ".part");
              try {
                Io.write(partial.toPath(), new TraceItem(file).metadata().toString());
                Files.move(partial.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE);
              } finally {
                partial.delete();
              }
            }
            Files.move(file.toPath(), target.toPath());
            moved = true;
            if (metadata.exists()) Files.move(metadata.toPath(), targetMetadata.toPath());
            feedback.postValue(R.string.saved);
            if (file.getPath().equals(detailPath)) {
              detailPath = target.getPath();
              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(() -> state.set("detail_path", target.getPath()));
              RecordingContext recording = repository.recording(target);
              detail.postValue(recording == null ? null : new Item(getApplication(), recording));
            }
          } catch (Exception e) {
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
