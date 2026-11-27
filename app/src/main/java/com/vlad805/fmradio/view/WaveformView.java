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
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import java.util.Arrays;

/**
 * Material 3 vertical-bar waveform seekbar with optional RDS-change marker ticks
 * and interactive trim range selection handles.
 */
public class WaveformView extends View {
    public interface OnSeekListener {
        void onSeekStart();

        void onSeekChanged(float fraction);

        void onSeekEnd(float fraction);
    }

    public interface OnTrimRangeChangeListener {
        void onTrimRangeChanged(float startFraction, float endFraction, boolean fromUser);
    }

    private static final int DRAG_NONE = 0;
    private static final int DRAG_PLAYHEAD = 1;
    private static final int DRAG_TRIM_START = 2;
    private static final int DRAG_TRIM_END = 3;

    private final Paint mPlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mUnplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSelectedUnplayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDimmedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlayheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarkerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSelectionFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHandlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBarRect = new RectF();

    private byte[] mPeaks = new byte[0];
    private float[] mMarkers = new float[0];
    private float mProgress = 0f;

    private boolean mTrimMode = false;
    private float mTrimStart = 0f;
    private float mTrimEnd = 1f;
    private float mMinTrimSpan = 0.02f;

    private int mDragTarget = DRAG_NONE;
    @Nullable
    private OnSeekListener mSeekListener;
    @Nullable
    private OnTrimRangeChangeListener mTrimListener;

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
        final int primary = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                0xFF2196F3
        );
        final int outline = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutlineVariant,
                0x66888888
        );

        mPlayedPaint.setStyle(Paint.Style.FILL);
        mPlayedPaint.setColor(primary);

        mUnplayedPaint.setStyle(Paint.Style.FILL);
        mUnplayedPaint.setColor(outline);

        // Selected bars ahead of playhead stay visibly tinted in trim mode regardless of playhead position.
        mSelectedUnplayedPaint.setStyle(Paint.Style.FILL);
        mSelectedUnplayedPaint.setColor(ColorUtils.setAlphaComponent(primary, 130));

        mDimmedPaint.setStyle(Paint.Style.FILL);
        mDimmedPaint.setColor(ColorUtils.setAlphaComponent(outline, 56));

        mPlayheadPaint.setStyle(Paint.Style.FILL);
        mPlayheadPaint.setColor(primary);

        mMarkerPaint.setStyle(Paint.Style.STROKE);
        mMarkerPaint.setStrokeWidth(Math.max(1f, density));
        mMarkerPaint.setColor(MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorTertiary,
                0x99FF9800
        ));

        mSelectionFillPaint.setStyle(Paint.Style.FILL);
        mSelectionFillPaint.setColor(ColorUtils.setAlphaComponent(primary, 32));

        mHandlePaint.setStyle(Paint.Style.FILL);
        mHandlePaint.setColor(primary);
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

    public void setTrimMode(final boolean enabled) {
        mTrimMode = enabled;
        mDragTarget = DRAG_NONE;
        invalidate();
    }

    public boolean isTrimMode() {
        return mTrimMode;
    }

    public void setMinTrimSpanFraction(final float minSpan) {
        mMinTrimSpan = Math.max(0.005f, Math.min(0.95f, minSpan));
    }

    public void setTrimRange(final float startFraction, final float endFraction) {
        mTrimStart = clamp01(startFraction);
        mTrimEnd = Math.max(mTrimStart, clamp01(endFraction));
        invalidate();
    }

    public void setOnTrimRangeChangeListener(@Nullable final OnTrimRangeChangeListener listener) {
        mTrimListener = listener;
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
        final float maxBarHeight = Math.max(minBarHeight, height - 10f * density);

        final float trimStartX = padLeft + mTrimStart * width;
        final float trimEndX = padLeft + mTrimEnd * width;

        // Draw tinted selection band behind bars when trim mode is active.
        if (mTrimMode) {
            mBarRect.set(trimStartX, padTop + 2f * density, trimEndX, padTop + height - 2f * density);
            canvas.drawRoundRect(mBarRect, 6f * density, 6f * density, mSelectionFillPaint);
        }

        // Draw subtle RDS-change markers behind the bars.
        for (final float marker : mMarkers) {
            if (marker <= 0f || marker >= 1f) {
                continue;
            }
            final float x = padLeft + marker * width;
            canvas.drawLine(x, padTop + 2f * density, x, padTop + height - 2f * density, mMarkerPaint);
        }

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
            final Paint paint;

            if (mTrimMode) {
                if (cx < trimStartX || cx > trimEndX) {
                    paint = mDimmedPaint;
                } else {
                    paint = cx <= playheadX ? mPlayedPaint : mSelectedUnplayedPaint;
                }
            } else {
                paint = cx <= playheadX ? mPlayedPaint : mUnplayedPaint;
            }

            canvas.drawRoundRect(mBarRect, radius, radius, paint);
        }

        // Draw playhead line.
        final float playheadHalfW = Math.max(1f * density, 1f);
        mBarRect.set(
                playheadX - playheadHalfW,
                padTop + 2f * density,
                playheadX + playheadHalfW,
                padTop + height - 2f * density
        );
        canvas.drawRoundRect(mBarRect, playheadHalfW, playheadHalfW, mPlayheadPaint);

        // Draw draggable start/end handles in trim mode.
        if (mTrimMode) {
            final float handleHalfW = 2.5f * density;
            final float handleRadius = 2.5f * density;
            mBarRect.set(
                    trimStartX - handleHalfW,
                    padTop,
                    trimStartX + handleHalfW,
                    padTop + height
            );
            canvas.drawRoundRect(mBarRect, handleRadius, handleRadius, mHandlePaint);

            mBarRect.set(
                    trimEndX - handleHalfW,
                    padTop,
                    trimEndX + handleHalfW,
                    padTop + height
            );
            canvas.drawRoundRect(mBarRect, handleRadius, handleRadius, mHandlePaint);
        }
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

        final float x = event.getX();
        final float fraction = xToFraction(x);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);

                if (mTrimMode) {
                    mDragTarget = pickTrimDragTarget(x);
                    if (mDragTarget == DRAG_TRIM_START || mDragTarget == DRAG_TRIM_END) {
                        applyTrimDrag(fraction);
                        return true;
                    }
                } else {
                    mDragTarget = DRAG_PLAYHEAD;
                }

                final float clamped = mTrimMode
                        ? Math.max(mTrimStart, Math.min(mTrimEnd, fraction))
                        : fraction;
                mProgress = clamped;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekStart();
                    mSeekListener.onSeekChanged(clamped);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mDragTarget == DRAG_NONE) {
                    return false;
                }

                if (mDragTarget == DRAG_TRIM_START || mDragTarget == DRAG_TRIM_END) {
                    applyTrimDrag(fraction);
                    return true;
                }

                final float clamped = mTrimMode
                        ? Math.max(mTrimStart, Math.min(mTrimEnd, fraction))
                        : fraction;
                mProgress = clamped;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekChanged(clamped);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mDragTarget == DRAG_NONE) {
                    return false;
                }

                final int finishedTarget = mDragTarget;
                mDragTarget = DRAG_NONE;
                getParent().requestDisallowInterceptTouchEvent(false);

                if (finishedTarget == DRAG_TRIM_START || finishedTarget == DRAG_TRIM_END) {
                    applyTrimDrag(fraction);
                    return true;
                }

                final float clamped = mTrimMode
                        ? Math.max(mTrimStart, Math.min(mTrimEnd, fraction))
                        : fraction;
                mProgress = clamped;
                invalidate();

                if (mSeekListener != null) {
                    mSeekListener.onSeekEnd(clamped);
                }
                return true;
            }

            default: {
                return super.onTouchEvent(event);
            }
        }
    }

    private int pickTrimDragTarget(final float touchX) {
        final float density = getResources().getDisplayMetrics().density;
        final float hitRadius = 28f * density;
        final int padLeft = getPaddingLeft();
        final float usable = getWidth() - padLeft - getPaddingRight();

        if (usable <= 0f) {
            return DRAG_PLAYHEAD;
        }

        final float startX = padLeft + mTrimStart * usable;
        final float endX = padLeft + mTrimEnd * usable;
        final float distStart = Math.abs(touchX - startX);
        final float distEnd = Math.abs(touchX - endX);

        if (distStart <= hitRadius && distStart <= distEnd) {
            return DRAG_TRIM_START;
        }

        if (distEnd <= hitRadius) {
            return DRAG_TRIM_END;
        }

        return DRAG_PLAYHEAD;
    }

    private void applyTrimDrag(final float fraction) {
        if (mDragTarget == DRAG_TRIM_START) {
            mTrimStart = clamp01(Math.min(fraction, mTrimEnd - mMinTrimSpan));
            mProgress = mTrimStart;
        } else if (mDragTarget == DRAG_TRIM_END) {
            mTrimEnd = clamp01(Math.max(fraction, mTrimStart + mMinTrimSpan));
            mProgress = mTrimEnd;
        }

        invalidate();

        if (mTrimListener != null) {
            mTrimListener.onTrimRangeChanged(mTrimStart, mTrimEnd, true);
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
