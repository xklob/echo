package com.klob.echo.ui;

import android.app.Application;
import androidx.lifecycle.*;
import com.klob.echo.R;
import com.klob.echo.tools.*;
import java.util.concurrent.*;

/** Configuration changes retain a single running model download. */
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

  @Override
  protected void onCleared() {
    worker.shutdown();
  }
}
