package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Custom drum-style horizontal FM frequency dial written from scratch on Canvas.
 * Supports smooth one-handed drag, inertial fling, tap-to-step, and favorite station markers.
 */
public final class FrequencyDialView extends View {
    public interface OnFrequencyChangedListener {
        /**
         * Called on every frequency step while dragging or flinging so the UI can preview
         * the live frequency without spamming hardware tune commands.
         */
        default void onPreview(int kHz) {}

        /**
         * Called when the user releases the dial or the inertial fling settles on a final frequency.
         */
        void onChanged(int kHz);
    }

    private int mMinKhz = 87500;
    private int mMaxKhz = 108000;
    private int mSpacingKhz = 100;
    private int mCurrentKhz = 87500;

    // Continuous visual frequency in kHz while dragging/flinging
    private float mVisualKhz = 87500f;
    private int mLastCommittedKhz = 87500;

    private Set<Integer> mFavoriteFrequencies = Collections.emptySet();
    private OnFrequencyChangedListener mListener;

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMinorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMajorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNeedlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNeedleGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFavoritePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgeFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF mBoundsRect = new RectF();
    private final Path mClipPath = new Path();

    private final OverScroller mScroller;
    private VelocityTracker mVelocityTracker;
    private final int mTouchSlop;
    private final int mMinimumFlingVelocity;
    private final int mMaximumFlingVelocity;

    private float mDownX;
    private float mLastTouchX;
    private boolean mIsDragging;
    private boolean mIsFlinging;

    private LinearGradient mLeftFadeGradient;
    private LinearGradient mRightFadeGradient;
    private int mGradientWidth;

    public FrequencyDialView(final Context context) {
        this(context, null);
    }

    public FrequencyDialView(final Context context, @Nullable final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FrequencyDialView(final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mScroller = new OverScroller(context);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinimumFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaximumFlingVelocity = vc.getScaledMaximumFlingVelocity();

        final float density = getResources().getDisplayMetrics().density;
        final float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(1f * density);

        mMinorTickPaint.setStyle(Paint.Style.STROKE);
        mMinorTickPaint.setStrokeWidth(1.5f * density);
        mMinorTickPaint.setStrokeCap(Paint.Cap.ROUND);

        mMajorTickPaint.setStyle(Paint.Style.STROKE);
        mMajorTickPaint.setStrokeWidth(2.2f * density);
        mMajorTickPaint.setStrokeCap(Paint.Cap.ROUND);

        mLabelPaint.setTextSize(11f * scaledDensity);
        mLabelPaint.setTextAlign(Paint.Align.CENTER);
        mLabelPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));

        mNeedlePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        mNeedlePaint.setStrokeWidth(2f * density);
        mNeedlePaint.setStrokeCap(Paint.Cap.BUTT);

        mNeedleGlowPaint.setStyle(Paint.Style.STROKE);
        mNeedleGlowPaint.setStrokeWidth(8f * density);
        mNeedleGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        mFavoritePaint.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setMinMaxValue(final int minKhz, final int maxKhz, final int spacingKhz) {
        mMinKhz = Math.min(minKhz, maxKhz);
        mMaxKhz = Math.max(minKhz, maxKhz);
        mSpacingKhz = Math.max(50, spacingKhz);
        setFrequency(mCurrentKhz);
        invalidate();
    }

    public void setFrequency(final int kHz) {
        final int clamped = clampAndSnap(kHz);
        if (isUserInteracting()) {
            return;
        }
        mCurrentKhz = clamped;
        mLastCommittedKhz = clamped;
        mVisualKhz = clamped;
        invalidate();
    }

    public boolean isUserInteracting() {
        return mIsDragging || mIsFlinging || !mScroller.isFinished();
    }

    public int getFrequency() {
        return mCurrentKhz;
    }

    public void setFavoriteFrequencies(@Nullable final Set<Integer> favorites) {
        if (favorites == null || favorites.isEmpty()) {
            mFavoriteFrequencies = Collections.emptySet();
        } else {
            mFavoriteFrequencies = new HashSet<>(favorites);
        }
        invalidate();
    }

    public void setOnFrequencyChangeListener(@Nullable final OnFrequencyChangedListener listener) {
        mListener = listener;
    }

    private int clampAndSnap(final float rawKhz) {
        final float clamped = Math.max(mMinKhz, Math.min(mMaxKhz, rawKhz));
        final int steps = Math.round((clamped - mMinKhz) / (float) mSpacingKhz);
        final int snapped = mMinKhz + steps * mSpacingKhz;
        return Math.max(mMinKhz, Math.min(mMaxKhz, snapped));
    }

    private float getPixelsPerKhz() {
        final float density = getResources().getDisplayMetrics().density;
        // 100 kHz = 14dp on screen -> 1 MHz = 140dp, comfortable for one-handed thumb scrolling
        return (14f * density) / 100f;
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildGradients(w);
    }

    private void rebuildGradients(final int width) {
        if (width <= 0) {
            return;
        }
        mGradientWidth = width;
        final int bgColor = getDialBackgroundColor();
        final int transparentBg = bgColor & 0x00FFFFFF;
        final float fadeZone = Math.min(width * 0.22f, 48f * getResources().getDisplayMetrics().density);

        mLeftFadeGradient = new LinearGradient(
                0, 0, fadeZone, 0,
                bgColor, transparentBg,
                Shader.TileMode.CLAMP
        );
        mRightFadeGradient = new LinearGradient(
                width - fadeZone, 0, width, 0,
                transparentBg, bgColor,
                Shader.TileMode.CLAMP
        );
    }

    private int getDialBackgroundColor() {
        return MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainerHighest,
                ContextCompat.getColor(getContext(), R.color.rfm_md_secondary_container)
        );
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        if (mGradientWidth != width || mLeftFadeGradient == null) {
            rebuildGradients(width);
        }

        final float density = getResources().getDisplayMetrics().density;

        final int bgColor = getDialBackgroundColor();
        final int strokeColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutlineVariant,
                ContextCompat.getColor(getContext(), R.color.rfm_md_secondary_container)
        );
        final int minorColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOutline,
                ContextCompat.getColor(getContext(), R.color.text_secondary)
        );
        final int majorColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(getContext(), R.color.text_secondary)
        );
        final int labelColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(getContext(), R.color.text_secondary)
        );
        final int needleColor = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(getContext(), R.color.rfm_md_primary)
        );
        final int favColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorTertiary,
                ContextCompat.getColor(getContext(), R.color.rfm_md_tertiary)
        );

        mBgPaint.setColor(bgColor);
        mBorderPaint.setColor(strokeColor);
        mMinorTickPaint.setColor(minorColor);
        mMinorTickPaint.setAlpha(150);
        mMajorTickPaint.setColor(majorColor);
        mLabelPaint.setColor(labelColor);
        mNeedlePaint.setColor(needleColor);
        mNeedleGlowPaint.setColor(needleColor);
        mNeedleGlowPaint.setAlpha(36);
        mFavoritePaint.setColor(favColor);

        mBoundsRect.set(0, 0, width, height);
        canvas.drawRect(mBoundsRect, mBgPaint);

        final int saveCount = canvas.save();
        mClipPath.reset();
        mClipPath.addRect(mBoundsRect, Path.Direction.CW);
        canvas.clipPath(mClipPath);

        final float centerX = width * 0.5f;
        final float pxPerKhz = getPixelsPerKhz();
        final float halfSpanKhz = (width * 0.5f) / pxPerKhz + 500f;

        final int drawStepKhz = 100;
        final int firstTickKhz = Math.max(
                mMinKhz,
                ((int) Math.floor((mVisualKhz - halfSpanKhz) / drawStepKhz)) * drawStepKhz
        );
        final int lastTickKhz = Math.min(
                mMaxKhz,
                ((int) Math.ceil((mVisualKhz + halfSpanKhz) / drawStepKhz)) * drawStepKhz
        );

        final float majorTickTop = height * 0.18f;
        final float majorTickBottom = height * 0.68f;
        final float mediumTickTop = height * 0.28f;
        final float mediumTickBottom = height * 0.64f;
        final float minorTickTop = height * 0.34f;
        final float minorTickBottom = height * 0.58f;
        final float labelBaseline = height - 6f * density;

        for (int khz = firstTickKhz; khz <= lastTickKhz; khz += drawStepKhz) {
            final float x = centerX + (khz - mVisualKhz) * pxPerKhz;

            final boolean isFullMhz = (khz % 1000) == 0;
            final boolean isHalfMhz = (khz % 500) == 0;

            if (isFullMhz) {
                canvas.drawLine(x, majorTickTop, x, majorTickBottom, mMajorTickPaint);
                final String label = String.format(Locale.US, "%.1f", khz / 1000f);
                canvas.drawText(label, x, labelBaseline, mLabelPaint);
            } else if (isHalfMhz) {
                canvas.drawLine(x, mediumTickTop, x, mediumTickBottom, mMajorTickPaint);
            } else {
                canvas.drawLine(x, minorTickTop, x, minorTickBottom, mMinorTickPaint);
            }
        }

        // Draw 50 kHz ticks when 50 kHz spacing is active
        if (mSpacingKhz == 50) {
            for (int khz = firstTickKhz + 50; khz < lastTickKhz; khz += 100) {
                if (khz < mMinKhz || khz > mMaxKhz) {
                    continue;
                }
                final float x = centerX + (khz - mVisualKhz) * pxPerKhz;
                canvas.drawLine(x, height * 0.40f, x, minorTickBottom, mMinorTickPaint);
            }
        }

        // Draw favorite station markers as glowing dots along top edge of ticks
        if (!mFavoriteFrequencies.isEmpty()) {
            final float favY = height * 0.12f;
            final float favRadius = 2.6f * density;
            for (final Integer favKhz : mFavoriteFrequencies) {
                if (favKhz == null || favKhz < firstTickKhz || favKhz > lastTickKhz) {
                    continue;
                }
                final float x = centerX + (favKhz - mVisualKhz) * pxPerKhz;
                canvas.drawCircle(x, favY, favRadius, mFavoritePaint);
            }
        }

        // Draw left/right edge fade to give 3D drum depth
        final float fadeZone = Math.min(width * 0.22f, 48f * density);
        if (mLeftFadeGradient != null) {
            mEdgeFadePaint.setShader(mLeftFadeGradient);
            canvas.drawRect(0, 0, fadeZone, height, mEdgeFadePaint);
        }
        if (mRightFadeGradient != null) {
            mEdgeFadePaint.setShader(mRightFadeGradient);
            canvas.drawRect(width - fadeZone, 0, width, height, mEdgeFadePaint);
        }

        // Draw full-height center needle
        canvas.drawLine(centerX, 0, centerX, height, mNeedleGlowPaint);
        canvas.drawLine(centerX, 0, centerX, height, mNeedlePaint);

        canvas.restoreToCount(saveCount);

        // Top and bottom hairline borders for edge-to-edge separation
        final float strokeW = 1f * density;
        mBorderPaint.setStrokeWidth(strokeW);
        canvas.drawLine(0, strokeW * 0.5f, width, strokeW * 0.5f, mBorderPaint);
        canvas.drawLine(0, height - strokeW * 0.5f, width, height - strokeW * 0.5f, mBorderPaint);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        if (mScroller.computeScrollOffset()) {
            final float targetKhz = mScroller.getCurrX();
            updateVisualFrequency(targetKhz);
            postInvalidateOnAnimation();
        } else if (mIsFlinging) {
            mIsFlinging = false;
            final int snapped = clampAndSnap(mVisualKhz);
            mVisualKhz = snapped;
            settleAndCommit(snapped);
            invalidate();
        }
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);

        final int action = event.getActionMasked();
        final float x = event.getX();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (!mScroller.isFinished()) {
                    mScroller.abortAnimation();
                }
                mIsFlinging = false;
                mDownX = x;
                mLastTouchX = x;
                mIsDragging = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                final float dx = x - mLastTouchX;
                if (!mIsDragging && Math.abs(x - mDownX) > mTouchSlop) {
                    mIsDragging = true;
                }
                if (mIsDragging) {
                    mLastTouchX = x;
                    // Dragging finger right moves scale right (frequency decreases)
                    final float deltaKhz = -dx / getPixelsPerKhz();
                    updateVisualFrequency(mVisualKhz + deltaKhz);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (mIsDragging) {
                    mVelocityTracker.computeCurrentVelocity(1000, mMaximumFlingVelocity);
                    final float vx = mVelocityTracker.getXVelocity();
                    mIsDragging = false;

                    if (Math.abs(vx) > mMinimumFlingVelocity) {
                        mIsFlinging = true;
                        final int velocityKhzPerSec = Math.round(-vx / getPixelsPerKhz());
                        mScroller.fling(
                                Math.round(mVisualKhz),
                                0,
                                velocityKhzPerSec,
                                0,
                                mMinKhz,
                                mMaxKhz,
                                0,
                                0
                        );
                        postInvalidateOnAnimation();
                    } else {
                        mIsFlinging = false;
                        final int snapped = clampAndSnap(mVisualKhz);
                        mVisualKhz = snapped;
                        settleAndCommit(snapped);
                        invalidate();
                    }
                } else {
                    // Quick tap on left or right side of the needle steps or jumps to tapped frequency
                    final float centerX = getWidth() * 0.5f;
                    final float offsetFromCenter = x - centerX;
                    if (Math.abs(offsetFromCenter) > 12f * getResources().getDisplayMetrics().density) {
                        final float tappedKhz = mVisualKhz + (offsetFromCenter / getPixelsPerKhz());
                        final int snapped = clampAndSnap(tappedKhz);
                        mVisualKhz = snapped;
                        if (snapped != mCurrentKhz) {
                            mCurrentKhz = snapped;
                            tickHaptic();
                            if (mListener != null) {
                                mListener.onPreview(snapped);
                            }
                        }
                        settleAndCommit(snapped);
                        invalidate();
                    }
                    performClick();
                }
                recycleVelocityTracker();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                mIsDragging = false;
                mIsFlinging = false;
                mScroller.abortAnimation();
                final int snapped = clampAndSnap(mVisualKhz);
                mVisualKhz = snapped;
                settleAndCommit(snapped);
                invalidate();
                recycleVelocityTracker();
                return true;
            }
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void updateVisualFrequency(final float newVisualKhz) {
        mVisualKhz = Math.max(mMinKhz, Math.min(mMaxKhz, newVisualKhz));
        final int snapped = clampAndSnap(mVisualKhz);
        if (snapped != mCurrentKhz) {
            mCurrentKhz = snapped;
            tickHaptic();
            if (mListener != null) {
                mListener.onPreview(snapped);
            }
        }
        invalidate();
    }

    private void settleAndCommit(final int snappedKhz) {
        mCurrentKhz = snappedKhz;
        if (mListener != null) {
            mListener.onPreview(snappedKhz);
        }
        if (snappedKhz != mLastCommittedKhz) {
            mLastCommittedKhz = snappedKhz;
            if (mListener != null) {
                mListener.onChanged(snappedKhz);
            }
        }
    }

    private void tickHaptic() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            performHapticFeedback(
                    HapticFeedbackConstants.CLOCK_TICK,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        } else {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
}
