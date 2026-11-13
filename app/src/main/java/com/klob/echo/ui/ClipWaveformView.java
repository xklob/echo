package com.klob.echo.ui;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import com.klob.echo.R;
import com.klob.echo.speech.ClipAnalysis;
import java.util.*;
import java.util.function.LongConsumer;

/** Detailed min/max waveform with a time axis, speech bands, direct scrubbing and pinch zoom. */
final class ClipWaveformView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ScaleGestureDetector scale;
  private ClipAnalysis.Waveform waveform;
  private List<long[]> speech = Collections.emptyList();
  private long duration = 1, position;
  private double windowStart, visible = 1;
  private float visualGain = 1;
  private boolean touching;
  private LongConsumer seek = value -> {};

  ClipWaveformView(Context context) {
    super(context);
    setId(R.id.clip_waveform);
    setFocusable(true);
    setClickable(true);
    setContentDescription(context.getString(R.string.waveform_help));
    scale =
        new ScaleGestureDetector(
            context,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                double fraction =
                    Math.max(0, Math.min(1, detector.getFocusX() / Math.max(1, getWidth())));
                double anchor = windowStart + visible * fraction;
                visible =
                    Math.max(
                        Math.min(2000, duration),
                        Math.min(duration, visible / detector.getScaleFactor()));
                windowStart =
                    Math.max(0, Math.min(duration - visible, anchor - visible * fraction));
                invalidate();
                return true;
              }
            });
  }

  void setSeekListener(LongConsumer seek) {
    this.seek = seek;
  }

  void data(ClipAnalysis.Waveform waveform, List<long[]> speech) {
    if (this.waveform != waveform && waveform != null) {
      int peak = 1;
      for (int i = 0; i < waveform.lows.length; i++)
        peak =
            Math.max(
                peak,
                Math.max(Math.abs((int) waveform.lows[i]), Math.abs((int) waveform.highs[i])));
      visualGain = Math.min(100, 29491f / peak);
    }
    this.waveform = waveform;
    this.speech = speech;
    long next = Math.max(1, waveform == null ? duration : waveform.duration());
    if (duration != next) {
      duration = next;
      visible = duration;
      windowStart = 0;
    }
    invalidate();
  }

  void duration(long value) {
    if (waveform == null) {
      duration = Math.max(1, value);
      visible = duration;
    }
  }

  void position(long value) {
    position = Math.max(0, Math.min(duration, value));
    if (!touching && (position < windowStart || position > windowStart + visible))
      windowStart = Math.max(0, Math.min(duration - visible, position - visible / 2));
    invalidate();
  }

  void zoom(double factor) {
    visible = Math.max(Math.min(2000, duration), Math.min(duration, visible * factor));
    windowStart = Math.max(0, Math.min(duration - visible, position - visible / 2));
    invalidate();
  }

  @Override
  protected void onMeasure(int width, int height) {
    super.onMeasure(
        width, MeasureSpec.makeMeasureSpec(Ui.dp(getContext(), 190), MeasureSpec.EXACTLY));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth(),
        top = Ui.dp(getContext(), 24),
        bottom = getHeight() - Ui.dp(getContext(), 28);
    float mid = (top + bottom) / 2, amplitude = (bottom - top) / 2;
    int accent = Ui.color(getContext(), androidx.appcompat.R.attr.colorPrimary);
    for (long[] span : speech) {
      float left = x(span[0]), right = x(span[1]);
      if (right < 0 || left > width) continue;
      paint.setColor(accent);
      paint.setAlpha(35);
      canvas.drawRect(Math.max(0, left), top, Math.min(width, right), bottom, paint);
      paint.setAlpha(255);
      canvas.drawRect(
          Math.max(0, left), top, Math.min(width, right), top + Ui.dp(getContext(), 3), paint);
    }
    paint.setColor(
        Ui.color(getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant));
    paint.setStrokeWidth(Ui.dp(getContext(), 1));
    if (waveform != null && waveform.lows.length > 0) {
      for (int pixel = 0; pixel < width; pixel += 2) {
        int from = bin(windowStart + pixel / width * visible);
        int to =
            Math.min(
                waveform.lows.length,
                Math.max(from + 1, bin(windowStart + (pixel + 2) / width * visible)));
        short low = 0, high = 0;
        for (int i = Math.max(0, from); i < to; i++) {
          low = (short) Math.min(low, waveform.lows[i]);
          high = (short) Math.max(high, waveform.highs[i]);
        }
        canvas.drawLine(
            pixel,
            mid - high / 32768f * amplitude * visualGain,
            pixel,
            mid - low / 32768f * amplitude * visualGain + 1,
            paint);
      }
    } else {
      paint.setTextSize(Ui.dp(getContext(), 14));
      canvas.drawText(
          getContext().getString(R.string.waveform_loading), Ui.dp(getContext(), 8), mid, paint);
    }
    paint.setAlpha(120);
    canvas.drawLine(0, mid, width, mid, paint);
    paint.setAlpha(255);
    paint.setTextSize(Ui.dp(getContext(), 11));
    for (int i = 0; i <= 4; i++) {
      long time = Math.round(windowStart + visible * i / 4);
      String label =
          visible <= 10000
              ? String.format(
                  java.util.Locale.ROOT,
                  "%d:%02d.%d",
                  time / 60000,
                  time / 1000 % 60,
                  time % 1000 / 100)
              : LibraryText.duration(time);
      float left =
          Math.max(
              0,
              Math.min(
                  width - paint.measureText(label), width * i / 4 - paint.measureText(label) / 2));
      canvas.drawText(label, left, getHeight() - Ui.dp(getContext(), 7), paint);
    }
    paint.setColor(accent);
    paint.setStrokeWidth(Ui.dp(getContext(), 2));
    float cursor = x(position);
    canvas.drawLine(cursor, top - Ui.dp(getContext(), 7), cursor, bottom, paint);
    canvas.drawCircle(cursor, top - Ui.dp(getContext(), 7), Ui.dp(getContext(), 4), paint);
  }

  private int bin(double ms) {
    return (int) (ms * 16 / waveform.width);
  }

  private float x(double ms) {
    return (float) ((ms - windowStart) / visible * getWidth());
  }

  private void scrub(float x) {
    position =
        Math.round(
            Math.max(
                0,
                Math.min(
                    duration,
                    windowStart
                        + Math.max(0, Math.min(getWidth(), x))
                            / Math.max(1, getWidth())
                            * visible)));
    seek.accept(position);
    invalidate();
  }

  @Override
  public boolean onTouchEvent(android.view.MotionEvent event) {
    scale.onTouchEvent(event);
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      touching = true;
      getParent().requestDisallowInterceptTouchEvent(true);
      scrub(event.getX());
      return true;
    }
    if (event.getActionMasked() == MotionEvent.ACTION_MOVE
        && !scale.isInProgress()
        && event.getPointerCount() == 1) scrub(event.getX());
    if (event.getActionMasked() == MotionEvent.ACTION_UP
        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      touching = false;
      getParent().requestDisallowInterceptTouchEvent(false);
      if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.SeekBar");
    info.setRangeInfo(
        AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0, duration, position));
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle args) {
    long next = position;
    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) next += 5000;
    else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) next -= 5000;
    else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
        && args != null)
      next = (long) args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
    else return super.performAccessibilityAction(action, args);
    position(next);
    seek.accept(position);
    return true;
  }
}
