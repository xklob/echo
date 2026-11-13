package com.klob.echo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import com.klob.echo.R;
import com.klob.echo.core.AmplitudeHistory;

/** Fits the selected, actually available save window to the current view width. */
final class LiveWaveformView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private AmplitudeHistory.Snapshot history;

  LiveWaveformView(Context context) {
    super(context);
    setId(R.id.live_waveform);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  int bars() {
    return Math.max(32, Math.min(1024, getWidth() / Math.max(1, Ui.dp(getContext(), 2))));
  }

  void history(AmplitudeHistory.Snapshot snapshot) {
    history = snapshot;
    setContentDescription(
        getContext()
            .getString(
                R.string.live_waveform_description, Ui.duration(snapshot.end - snapshot.start)));
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth(), height = getHeight() - Ui.dp(getContext(), 26);
    if (width <= 0 || height <= 0) return;
    float middle = height / 2;
    paint.setStrokeWidth(Ui.dp(getContext(), 1));
    paint.setColor(Ui.color(getContext(), com.google.android.material.R.attr.colorOutlineVariant));
    canvas.drawLine(0, middle, width, middle, paint);
    if (history != null && history.low.length > 0) {
      int maximum = 1;
      for (int i = 0; i < history.low.length; i++)
        maximum = Math.max(maximum, Math.max(Math.abs((int) history.low[i]), history.high[i]));
      float scale = (middle - Ui.dp(getContext(), 4)) / Math.max(327.68f, maximum);
      float step = width / history.low.length;
      paint.setStrokeWidth(Math.max(1, Math.min(Ui.dp(getContext(), 2), step)));
      paint.setColor(Ui.color(getContext(), androidx.appcompat.R.attr.colorPrimary));
      for (int i = 0; i < history.low.length; i++) {
        float x = (i + 0.5f) * step;
        canvas.drawLine(
            x, middle - history.high[i] * scale, x, middle - history.low[i] * scale, paint);
      }
    }
    paint.setColor(
        Ui.color(getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
    paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
    paint.setTypeface(getResources().getFont(R.font.space_grotesk));
    String start =
        history == null || history.end == history.start
            ? "0:00"
            : "−" + Ui.duration(history.end - history.start);
    float baseline = getHeight() - Ui.dp(getContext(), 3);
    canvas.drawText(start, 0, baseline, paint);
    String latest = getContext().getString(R.string.latest_audio);
    canvas.drawText(latest, width - paint.measureText(latest), baseline, paint);
  }
}
