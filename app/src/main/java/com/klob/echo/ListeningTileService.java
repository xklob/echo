package com.klob.echo;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.*;
import com.klob.echo.recording.RecordingService;
import com.klob.echo.ui.MainActivity;

/** Starts through a visible activity to honor while-in-use microphone permission. */
public final class ListeningTileService extends TileService {
  @Override
  public void onStartListening() {
    Tile tile = getQsTile();
    if (tile == null) return;
    tile.setLabel(getString(com.klob.echo.R.string.app_name));
    tile.setState(RecordingService.sessionActive ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.updateTile();
  }

  @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
  @Override
  public void onClick() {
    if (RecordingService.sessionActive) {
      try {
        startService(
            new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP));
      } catch (RuntimeException ignored) {
      }
    } else {
      Intent open =
          new Intent(this, MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              .putExtra("tile_start", true);
      if (Build.VERSION.SDK_INT >= 34)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
      else startActivityAndCollapse(open);
    }
    onStartListening();
  }
}
