package com.klob.echo.ui;

import android.app.Application;
import androidx.lifecycle.*;
import com.klob.echo.R;
import com.klob.echo.library.*;
import com.klob.echo.speech.*;
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
      String speechLabel = "";
      try {
        org.json.JSONObject metadata =
            new org.json.JSONObject(Io.read(new File(file.getPath() + ".json").toPath()));
        if (metadata.optBoolean("speech_analyzed"))
          speechLabel =
              app.getString(
                  metadata.optLong("speech_ms") > 0
                      ? R.string.speech_badge
                      : R.string.no_speech_badge);
        if (metadata.optBoolean("transcribed"))
          speechLabel +=
              (speechLabel.isEmpty() ? "" : " · ") + app.getString(R.string.transcript_badge);
      } catch (Exception ignored) {
      }
      String contextLabel = LibraryText.context(app, recording);
      contextText =
          contextLabel
              + (speechLabel.isEmpty() ? "" : (contextLabel.isEmpty() ? "" : "\n") + speechLabel);
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
          boolean ok;
          synchronized (ClipAnalysis.FILE_LOCK) {
            ok = file.delete();
            if (ok) {
              AnalysisWorker.cancel(getApplication(), file);
              for (File companion : companions(file))
                if (companion.exists() && !companion.delete()) ok = false;
            }
          }
          feedback.postValue(ok ? R.string.file_deleted : R.string.file_delete_failed);
          load();
          String path = detailPath;
          if (path != null && path.equals(file.getPath())) loadDetail(path);
        });
  }

  private static List<File> companions(File file) {
    List<File> result = new ArrayList<>();
    result.add(metadata(file));
    if (ClipStore.isAudio(file.getName()))
      for (String suffix : ClipAnalysis.SUFFIXES) result.add(ClipAnalysis.file(file, suffix));
    return result;
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
          List<File[]> moved = new ArrayList<>();
          synchronized (ClipAnalysis.FILE_LOCK) {
            try {
              if (target.exists()) throw new IOException();
              for (File companion : companions(target))
                if (companion.exists()) throw new IOException();
              if (!ClipStore.isAudio(file.getName()) && !metadata.exists()) {
                ClipAnalysis.atomicText(metadata, new TraceItem(file).metadata().toString());
              }
              List<File> before = companions(file), after = companions(target);
              before.add(file);
              after.add(target);
              for (int i = 0; i < before.size(); i++) {
                if (before.get(i).exists()) {
                  Files.move(before.get(i).toPath(), after.get(i).toPath());
                  moved.add(new File[] {before.get(i), after.get(i)});
                }
              }
              AnalysisWorker.cancel(getApplication(), file);
              feedback.postValue(R.string.saved);
              if (file.getPath().equals(detailPath)) {
                detailPath = target.getPath();
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> state.set("detail_path", target.getPath()));
                RecordingContext recording = repository.recording(target);
                detail.postValue(recording == null ? null : new Item(getApplication(), recording));
              }
            } catch (Exception e) {
              Collections.reverse(moved);
              for (File[] pair : moved)
                try {
                  Files.move(pair[1].toPath(), pair[0].toPath());
                } catch (IOException ignored) {
                }
              feedback.postValue(R.string.rename_failed);
            }
          }
          load();
        });
  }

  @Override
  protected void onCleared() {
    worker.shutdown();
  }
}
