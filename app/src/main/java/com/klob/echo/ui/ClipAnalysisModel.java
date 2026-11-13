package com.klob.echo.ui;

import android.app.Application;
import android.os.*;
import androidx.lifecycle.*;
import androidx.work.*;
import com.klob.echo.speech.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/** Holds analysis and progress across navigation/recreation; all sidecar reads run off the UI. */
public final class ClipAnalysisModel extends AndroidViewModel {
  static final class Display {
    final ClipAnalysis analysis;
    final ClipAnalysis.Waveform waveform;
    final boolean busy;
    final String status;

    Display(ClipAnalysis analysis, ClipAnalysis.Waveform waveform, boolean busy, String status) {
      this.analysis = analysis;
      this.waveform = waveform;
      this.busy = busy;
      this.status = status;
    }
  }

  final MediatorLiveData<Display> data = new MediatorLiveData<>();
  private final ExecutorService reader = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private LiveData<List<WorkInfo>> work;
  private File file;
  private ClipAnalysis analysis = new ClipAnalysis();
  private ClipAnalysis.Waveform waveform;
  private boolean busy, scheduled;
  private String status = "";
  private String completedRevision = "";

  public ClipAnalysisModel(Application application) {
    super(application);
  }

  void open(File next) {
    if (next.equals(file)) {
      refresh();
      return;
    }
    if (work != null) data.removeSource(work);
    file = next;
    scheduled = false;
    waveform = null;
    analysis = new ClipAnalysis();
    completedRevision = "";
    busy = false;
    status = "";
    emit();
    work =
        WorkManager.getInstance(getApplication())
            .getWorkInfosByTagLiveData(AnalysisWorker.tag(file));
    data.addSource(work, this::progress);
    refresh();
  }

  private void progress(List<WorkInfo> jobs) {
    busy = false;
    status = "";
    long newest = -1;
    WorkInfo latest = null;
    StringBuilder completed = new StringBuilder();
    for (WorkInfo job : jobs) {
      long created = 0;
      for (String tag : job.getTags())
        if (tag.startsWith("analysis-created:"))
          try {
            created = Long.parseLong(tag.substring(17));
          } catch (NumberFormatException ignored) {
          }
      if (created >= newest) {
        newest = created;
        latest = job;
      }
      if (!job.getState().isFinished()) {
        busy = true;
        long processed = job.getProgress().getLong("processed_ms", 0);
        if (job.getState() == WorkInfo.State.RUNNING)
          status = "Processing on this device · " + LibraryText.duration(processed) + " analyzed";
        else if (status.isEmpty()) status = "Waiting to process on this device";
      } else completed.append(job.getId()).append(job.getState());
    }
    if (!busy && latest != null && latest.getState() == WorkInfo.State.FAILED)
      status = latest.getOutputData().getString("error");
    if (!busy && latest != null && latest.getState() == WorkInfo.State.CANCELLED)
      status = "Processing cancelled. Saved results are unchanged.";
    emit();
    if (!completed.toString().equals(completedRevision)) {
      completedRevision = completed.toString();
      refresh();
    }
  }

  void request(String mode) {
    if (file != null) AnalysisWorker.enqueue(getApplication(), file, mode);
  }

  void cancel() {
    if (file != null) AnalysisWorker.cancel(getApplication(), file);
  }

  void retryWaveform() {
    if (!busy) scheduled = false;
    refresh();
  }

  void refresh() {
    final File current = file;
    if (current == null) return;
    reader.execute(
        () -> {
          ClipAnalysis result = ClipAnalysis.read(current);
          ClipAnalysis.Waveform peaks = ClipAnalysis.readWaveform(current);
          main.post(
              () -> {
                if (!current.equals(file)) return;
                analysis = result;
                waveform = peaks;
                emit();
                if (peaks == null && !scheduled) {
                  scheduled = true;
                  request("wave");
                }
              });
        });
  }

  private void emit() {
    data.setValue(new Display(analysis, waveform, busy, status == null ? "" : status));
  }

  @Override
  protected void onCleared() {
    reader.shutdown();
  }
}
