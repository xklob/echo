package com.klob.echo.ui;

import android.content.*;
import android.os.*;
import android.view.View;
import androidx.fragment.app.Fragment;
import com.google.android.material.snackbar.Snackbar;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.settings.AppSettings;

/** UI observes the existing recording owner; it never owns a microphone or capture worker. */
abstract class ServiceFragment extends Fragment {
  protected RecordingService service;
  private boolean bound;
  private Context boundContext;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder binder) {
          service = ((RecordingService.LocalBinder) binder).service();
          service.tools.restoreConfigured();
          render();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
          service = null;
          render();
        }
      };
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          if (getView() != null) render();
          handler.postDelayed(this, 500);
        }
      };

  @Override
  public void onStart() {
    super.onStart();
    boundContext = requireContext();
    bound =
        boundContext.bindService(
            new Intent(boundContext, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
    handler.post(tick);
  }

  @Override
  public void onStop() {
    handler.removeCallbacks(tick);
    if (bound) {
      boundContext.unbindService(connection);
      bound = false;
    }
    service = null;
    super.onStop();
  }

  protected SharedPreferences prefs() {
    return AppSettings.prefs(requireContext());
  }

  protected void message(int text) {
    message(getString(text));
  }

  protected void message(CharSequence text) {
    View view = getView();
    if (view != null) Snackbar.make(view, text, Snackbar.LENGTH_LONG).show();
  }

  protected void render() {}
}
