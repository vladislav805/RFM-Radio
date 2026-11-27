package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

/**
 * Material 3 vertical-bar waveform seekbar with optional RDS-change marker ticks.
 * Designed with clean draw/touch separation so trim selection handles can be layered on top.
 */
public class WaveformView extends View {
    public interface OnSeekListener {
        void onSeekStart();

        void onSeekChanged(float fraction);

        void onSeekEnd(float fraction);
    }

    private static final int DEFAULT_BAR_COUNT = 80;

    private final Paint mPlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mUnplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBarRect = new RectF();

    private byte[] mPeaks = new byte[0];
    private float[] mMarkers = new float[0];
    private float mProgress = 0f;
    private boolean mDragging = false;
    @Nullable
    private OnSeekListener mSeekListener;

    public WaveformView(@NonNull final Context context) {
        super(context);
        init();
    }

    public WaveformView(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WaveformView(
            @NonNull final Context context,
            @Nullable final AttributeSet attrs,
            final int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        final float density = getResources().getDisplayMetrics().density;

        mPlayedPaint.setStyle(Paint.Style.FILL);
        mPlayedPaint.setColor(MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                0xFF2196F3
        ));

        mUnplayedPaint.setStyle(Paint.Style.FILL);
        mUnplayedPaint.setColor(MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutlineVariant,
                0x66888888
        ));

        mPlayheadPaint.setStyle(Paint.Style.FILL);
        mPlayheadPaint.setColor(MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                0xFF2196F3
        ));

        mMarkerPaint.setStyle(Paint.Style.STROKE);
        mMarkerPaint.setStrokeWidth(Math.max(1f, density));
        mMarkerPaint.setColor(MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorTertiary,
                0x99FF9800
        ));
    }

    public void setPeaks(@Nullable final byte[] peaks) {
        mPeaks = peaks != null ? Arrays.copyOf(peaks, peaks.length) : new byte[0];
        invalidate();
    }

    public void setMarkers(@Nullable final float[] markerFractions) {
        mMarkers = markerFractions != null ? Arrays.copyOf(markerFractions, markerFractions.length) : new float[0];
        invalidate();
    }

    public void setProgress(final float fraction) {
        final float clamped = clamp01(fraction);

        if (Math.abs(clamped - mProgress) < 0.0005f) {
            return;
        }

        mProgress = clamped;
        invalidate();
    }

    public float getProgress() {
        return mProgress;
    }

    public void setOnSeekListener(@Nullable final OnSeekListener listener) {
        mSeekListener = listener;
    }

    @Override
    protected void onDraw(@NonNull final Canvas canvas) {
        super.onDraw(canvas);

        final int padLeft = getPaddingLeft();
        final int padRight = getPaddingRight();
        final int padTop = getPaddingTop();
        final int padBottom = getPaddingBottom();

        final float width = getWidth() - padLeft - padRight;
        final float height = getHeight() - padTop - padBottom;

        if (width <= 0f || height <= 0f) {
            return;
        }

        final float density = getResources().getDisplayMetrics().density;
        final float centerY = padTop + height / 2f;
        final float minBarHeight = Math.max(3f * density, 2f);
        final float maxBarHeight = Math.max(minBarHeight, height - 8f * density);

        // Draw subtle RDS-change markers behind the bars.
        for (final float marker : mMarkers) {
            if (marker <= 0f || marker >= 1f) {
                continue;
            }
            final float x = padLeft + marker * width;
            canvas.drawLine(x, padTop + 2f * density, x, padTop + height - 2f * density, mMarkerPaint);
        }

        // Compute visible bar count based on available width (~4dp per bar slot).
        final float slotWidth = Math.max(3.5f * density, 3f);
        final int barCount = Math.max(16, Math.min(120, (int) (width / slotWidth)));
        final float step = width / barCount;
        final float barWidth = Math.max(2f * density, step * 0.56f);
        final float radius = barWidth / 2f;
        final float playheadX = padLeft + mProgress * width;

        for (int i = 0; i < barCount; i++) {
            final float amplitude = sampleAmplitude(i, barCount);
            final float barHeight = minBarHeight + amplitude * (maxBarHeight - minBarHeight);
            final float cx = padLeft + (i + 0.5f) * step;
            final float left = cx - barWidth / 2f;
            final float top = centerY - barHeight / 2f;

            mBarRect.set(left, top, left + barWidth, top + barHeight);
            final Paint paint = cx <= playheadX ? mPlayedPaint : mUnplayedPaint;
            canvas.drawRoundRect(mBarRect, radius, radius, paint);
        }

        // Draw playhead indicator line.
        final float playheadHalfW = Math.max(1f * density, 1f);
        mBarRect.set(
                playheadX - playheadHalfW,
                padTop + 2f * density,
                playheadX + playheadHalfW,
                padTop + height - 2f * density
        );
        canvas.drawRoundRect(mBarRect, playheadHalfW, playheadHalfW, mPlayheadPaint);
    }

    private float sampleAmplitude(final int barIndex, final int barCount) {
        if (mPeaks.length == 0) {
            return 0.12f;
        }

        final int start = (int) ((barIndex * (long) mPeaks.length) / barCount);
        final int end = Math.max(start + 1, (int) (((barIndex + 1L) * mPeaks.length) / barCount));
        int maxVal = 0;

        for (int i = start; i < end && i < mPeaks.length; i++) {
            final int v = mPeaks[i] & 0xFF;
            if (v > maxVal) {
                maxVal = v;
            }
        }

        return maxVal / 255f;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        final float fraction = xToFraction(event.getX());

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                mProgress = fraction;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekStart();
                    mSeekListener.onSeekChanged(fraction);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (!mDragging) {
                    return false;
                }

                mProgress = fraction;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekChanged(fraction);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!mDragging) {
                    return false;
                }

                mDragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                mProgress = fraction;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekEnd(fraction);
                }
                return true;
            }

            default: {
                return super.onTouchEvent(event);
            }
        }
    }

    protected float xToFraction(final float x) {
        final int padLeft = getPaddingLeft();
        final float usable = getWidth() - padLeft - getPaddingRight();

        if (usable <= 0f) {
            return 0f;
        }

        return clamp01((x - padLeft) / usable);
    }

    protected static float clamp01(final float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
