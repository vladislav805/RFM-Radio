package com.vlad805.fmradio.view;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;

public final class SignalStrengthView extends View {
    private static final int BAR_COUNT = 5;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBarRect = new RectF();
    private final ArgbEvaluator mColorEvaluator = new ArgbEvaluator();
    private int mLevel;
    private float mAnimatedLevel;
    private ValueAnimator mLevelAnimator;

    public SignalStrengthView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setMetrics(final int rmssi, final int sinr) {
        final int level = SignalStrength.levelForMetrics(rmssi, sinr);

        setLevel(level);
    }

    public void setRmssi(final int rmssi) {
        setMetrics(rmssi, Integer.MIN_VALUE);
    }

    public void clear() {
        setLevel(0);
    }

    private void setLevel(final int level) {
        if (mLevel == level) {
            return;
        }
        mLevel = level;
        if (mLevelAnimator != null) {
            mLevelAnimator.cancel();
            mLevelAnimator = null;
        }
        if (!isLaidOut()) {
            mAnimatedLevel = level;
            invalidate();
            return;
        }
        mLevelAnimator = ValueAnimator.ofFloat(mAnimatedLevel, (float) level);
        mLevelAnimator.setDuration(600L);
        mLevelAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        mLevelAnimator.addUpdateListener(animation -> {
            mAnimatedLevel = (float) animation.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        mLevelAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mLevelAnimator != null) {
            mLevelAnimator.cancel();
            mLevelAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final float density = getResources().getDisplayMetrics().density;
        final float gap = 2f * density;
        final float inset = 3f * density;
        final float radius = 1.5f * density;
        final float availableWidth = getWidth() - 2 * inset - gap * (BAR_COUNT - 1);
        final float barWidth = Math.max(2f * density, availableWidth / BAR_COUNT);
        final float bottom = getHeight() - inset;
        final float availableHeight = getHeight() - 2 * inset;

        final int activeColor = MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(getContext(), R.color.rfm_md_primary)
        );
        final int inactiveColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(getContext(), R.color.text_secondary)
        );

        for (int i = 0; i < BAR_COUNT; ++i) {
            final float left = inset + i * (barWidth + gap);
            final float height = availableHeight * (i + 1) / BAR_COUNT;
            final float fill = Math.max(0f, Math.min(1f, mAnimatedLevel - i));

            final int color = (int) mColorEvaluator.evaluate(fill, inactiveColor, activeColor);
            mPaint.setColor(color);
            mPaint.setAlpha(Math.round(70 + fill * (255 - 70)));
            mBarRect.set(left, bottom - height, left + barWidth, bottom);
            canvas.drawRoundRect(mBarRect, radius, radius, mPaint);
        }
    }
}
