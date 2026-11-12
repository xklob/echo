package com.klob.echo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import com.google.android.material.color.MaterialColors;

/** Adapted from ArturLinnik/toldya 26e2729, GPL-3.0. */
public class WaveformView extends View {

    private static final int BAR_COUNT = 32;
    private static final float BAR_MIN = 0.08f;
    private static final float BAR_GAP_RATIO = 0.4f;
    private static final float DECAY = 0.85f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] displayHeights = new float[BAR_COUNT];
    private final float[] targetHeights = new float[BAR_COUNT];
    private ValueAnimator animator;
    private boolean active = false;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary));

        for (int i = 0; i < BAR_COUNT; i++) {
            displayHeights[i] = BAR_MIN;
            targetHeights[i] = BAR_MIN;
        }
    }

    public void setAmplitudes(float[] amplitudes) {
        for (int i = 0; i < BAR_COUNT && i < amplitudes.length; i++) {
            float amp = (float) Math.sqrt(amplitudes[i]) * 2.0f;
            if (amp > 1.0f) amp = 1.0f;
            targetHeights[i] = BAR_MIN + (1.0f - BAR_MIN) * amp;
        }
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (active) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    public void setBarColor(int color) {
        paint.setColor(color);
    }

    private void startAnimation() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(10000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                for (int i = 0; i < BAR_COUNT; i++) {
                    if (targetHeights[i] > displayHeights[i]) {
                        displayHeights[i] = targetHeights[i];
                    } else {
                        displayHeights[i] = displayHeights[i] * DECAY + targetHeights[i] * (1 - DECAY);
                    }
                    if (displayHeights[i] < BAR_MIN) displayHeights[i] = BAR_MIN;
                }
                invalidate();
            }
        });
        animator.start();
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        for (int i = 0; i < BAR_COUNT; i++) {
            displayHeights[i] = BAR_MIN;
            targetHeights[i] = BAR_MIN;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float totalBarWidth = w / (BAR_COUNT + (BAR_COUNT - 1) * BAR_GAP_RATIO);
        float gap = totalBarWidth * BAR_GAP_RATIO;
        float barWidth = totalBarWidth;
        float radius = barWidth / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = h * displayHeights[i];
            float x = i * (barWidth + gap);
            float top = (h - barHeight) / 2f;
            canvas.drawRoundRect(x, top, x + barWidth, top + barHeight, radius, radius, paint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = (int) (40 * getResources().getDisplayMetrics().density);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
