package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public final class SignalStrengthView extends View {
    private static final int BAR_COUNT = 5;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mLevel;

    public SignalStrengthView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(Color.WHITE);
    }

    public void setRmssi(final int rmssi) {
        final int level = SignalStrength.levelForRmssi(rmssi);

        setLevel(level);
    }

    public void clear() {
        setLevel(0);
    }

    private void setLevel(final int level) {
        if (mLevel != level) {
            mLevel = level;
            invalidate();
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        final float density = getResources().getDisplayMetrics().density;
        final float gap = 1.5f * density;
        final float inset = 5f * density;
        final float availableWidth = getWidth() - 2 * inset - gap * (BAR_COUNT - 1);
        final float barWidth = availableWidth / BAR_COUNT;
        final float bottom = getHeight() - inset;
        final float availableHeight = getHeight() - 2 * inset;

        for (int i = 0; i < BAR_COUNT; ++i) {
            final float left = inset + i * (barWidth + gap);
            final float height = availableHeight * (i + 1) / BAR_COUNT;

            mPaint.setAlpha(i < mLevel ? 255 : 76);
            canvas.drawRect(left, bottom - height, left + barWidth, bottom, mPaint);
        }
    }
}
