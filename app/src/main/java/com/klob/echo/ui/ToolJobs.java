package com.klob.echo.ui;

import android.app.Application;
import android.net.Uri;
import androidx.lifecycle.*;
import com.klob.echo.R;
import com.klob.echo.storage.*;
import com.klob.echo.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Configuration changes retain a single running download or local save. */
public final class ToolJobs extends AndroidViewModel {
  public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
  public final MutableLiveData<Integer> result = new MutableLiveData<>(0);
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  private interface Job {
    void run() throws Exception;
  }

  public ToolJobs(Application app) {
    super(app);
  }

  private void run(Job job, int success, int failure) {
    if (Boolean.TRUE.equals(busy.getValue())) return;
    busy.setValue(true);
    result.setValue(0);
    worker.execute(
        () -> {
          int message;
          try {
            job.run();
            message = success;
          } catch (Exception e) {
            message = failure;
          }
          result.postValue(message);
          busy.postValue(false);
        });
  }

  public void model() {
    run(
        () -> ModelInstaller.install(getApplication()),
        R.string.model_installed,
        R.string.model_failed);
  }

  public void note(String text) {
    run(
        () -> new TraceStore(getApplication()).text("_note", ".txt", text),
        R.string.note_saved,
        R.string.note_failed);
  }

  public void answers(String json) {
    run(
        () -> new TraceStore(getApplication()).text("_survey", ".json", json),
        R.string.survey_saved,
        R.string.survey_save_failed);
  }

  public void importSurvey(Uri uri) {
    run(
        () -> {
          String json;
          try (InputStream in = getApplication().getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException();
            json = new String(Io.readBytes(in, 65536), java.nio.charset.StandardCharsets.UTF_8);
          }
          Survey.parse(json);
          File file = new File(getApplication().getFilesDir(), "survey.json");
          File partial = new File(file.getPath() + ".part");
          try {
            Io.write(partial.toPath(), json);
            Files.move(
                partial.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
          } finally {
            partial.delete();
          }
        },
        R.string.survey_imported,
        R.string.survey_invalid);
  }

  @Override
  protected void onCleared() {
    worker.shutdown();
  }
}
