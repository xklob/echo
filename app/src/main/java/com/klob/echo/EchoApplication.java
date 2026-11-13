package com.klob.echo;

import android.app.Application;
import com.klob.echo.settings.AppSettings;

public final class EchoApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    AppSettings.applyTheme(this);
  }
}
