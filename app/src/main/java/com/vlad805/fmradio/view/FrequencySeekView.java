package com.vlad805.fmradio.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

import com.vlad805.fmradio.R;

import java.util.Locale;

/**
 * vlad805 (c) 2018
 * Custom SeekBar
 */
@SuppressLint("AppCompatCustomView")
public class FrequencySeekView extends SeekBar {
	private Paint mTrait;
	private Paint mFrequency0;
	private Paint mFrequency5;
	private Paint mCurrentLine;
	private Paint mStationLine;

	private int mValueMin = 0;
	private int mValueMax = 100;
	private int mSpacing = 10;

	private int mWidthPx;

	public FrequencySeekView(final Context context) {
		super(context);
		init();
	}

	public FrequencySeekView(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	private void init() {
		final Resources resources = getResources();
		final float dpi = resources.getDisplayMetrics().density;

		mTrait = new Paint();
		mTrait.setColor(Color.GRAY);
		mTrait.setStrokeWidth(dpi);
		mTrait.setStyle(Paint.Style.STROKE);

		mFrequency0 = new Paint();
		mFrequency0.setColor(Color.WHITE);
		mFrequency0.setTextSize(resources.getDimensionPixelSize(R.dimen.seek_frequency_frequency_text_0));
		mFrequency0.setTextAlign(Paint.Align.RIGHT);
		mFrequency0.setTypeface(Typeface.MONOSPACE);
		mFrequency0.setAntiAlias(true);

		mFrequency5 = new Paint();
		mFrequency5.setColor(Color.LTGRAY);
		mFrequency5.setTextSize(resources.getDimensionPixelSize(R.dimen.seek_frequency_frequency_text_5));
		mFrequency5.setTextAlign(Paint.Align.RIGHT);
		mFrequency5.setTypeface(Typeface.MONOSPACE);
		mFrequency5.setAntiAlias(true);

		mCurrentLine = new Paint();
		mCurrentLine.setColor(Color.RED);
		mCurrentLine.setStrokeWidth(dpi * 2f);
		mCurrentLine.setStyle(Paint.Style.STROKE);

		mStationLine = new Paint();
		mStationLine.setColor(Color.YELLOW);
		mStationLine.setStrokeWidth(dpi);
		mStationLine.setStyle(Paint.Style.STROKE);

		mWidthPx = resources.getDimensionPixelOffset(R.dimen.seek_frequency_height);

		invalidate();
	}

	protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		super.onSizeChanged(h, w, oldH, oldW);
	}

	private void setMaxValue(final int maxValue) {
		mValueMax = maxValue;
	}

	private void setMinValue(final int minValue) {
		mValueMin = minValue;
	}

	public void setSpacing(final int spacing) {
		mSpacing = spacing;
	}

	/**
	 * Change edges
	 * @param minValue Lower frequency in kHz
	 * @param maxValue Higher frequency in kHz
	 * @param spacing Spacing in kHz
	 */
	public void setMinMaxValue(final int minValue, final int maxValue, final int spacing) {
		setMinValue(minValue);
		setMaxValue(maxValue);
		setSpacing(spacing);
		setMax((mValueMax - mValueMin) / mSpacing);
	}

	/**
	 * Returns current frequency
	 * @param progress Value by SeekBar
	 * @return Frequency in kHz
	 */
	public int fixProgress(final int progress) {
		return progress * mSpacing + mValueMin;
	}

	@Override
	protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // noinspection SuspiciousNameCombination
        super.onMeasure(heightMeasureSpec, widthMeasureSpec);
		setMeasuredDimension(getMeasuredHeight(), getMeasuredWidth());
	}

	/**
	 * Change current frequency
	 * @param kHz Frequency in kHz
	 */
	@Override
	public synchronized void setProgress(final int kHz) {
		if (mSpacing != 0) {
			super.setProgress((kHz - mValueMin) / mSpacing);
		}
	}

	/**
	 * Returns current frequency in kHz
	 * @return Frequency in kHz
	 */
	@Override
	public synchronized int getProgress() {
		return fixProgress(super.getProgress());
	}

	@Override
	protected void onDraw(final Canvas canvas) {
		// Because SeekView is rotated
		final int height = getHeight();

		// Height of view
		final int viewHeight = height - getPaddingBottom() - getPaddingTop();

		// Padding from left side
		final int deltaY = getPaddingTop();

		// Computed width of interval between dashes of frequencies
		final float viewInterval = viewHeight / ((mValueMax - mValueMin) * 1f / mSpacing);

		// x-coordinate, end of short stick relative to the right
		final int baseXShort = 25;

		// x-coordinate, end of long dash relative to the right
		final int baseXLong = 60;

		// x-coordinate, position of label .0 relative to the right
		final int baseXText = mWidthPx - baseXLong - 5;

		final float baseYText0 = mFrequency0.getTextSize() / 2;
		final float baseYText5 = mFrequency5.getTextSize() / 2;

		// Selected kHz
		final int kHzCurrent = getProgress();

		// For each 100 kHz ...
		for (int i = 0; i <= (mValueMax - mValueMin) / mSpacing; ++i) {

			// kHz frequency for current iteration
			final int kHz = mValueMin + i * mSpacing;

			// Y coordinate for current iteration
			final float y = deltaY + viewInterval * i;

			// The presence of the current frequency in the list of stations
			final boolean hasStation = false; // mStations != null && mStations.contains(kHz);

			// Choose color for dash in depend of presence of the current frequency in the list of stations
			@SuppressWarnings("ConstantConditions")
			final Paint colorTrait = hasStation ? mStationLine : mTrait;

			final boolean isRoundMHz = kHz % 1000 == 0;

			// Label
			final String MHz = String.format(Locale.ENGLISH, isRoundMHz ? "%.0f" : "%.1f", kHz / 1000f);

			// If .0 MHz or .5 MHz
			if (kHz % 500 == 0) {
				// Draw long dash
				canvas.drawLine(
						mWidthPx - baseXLong,
						y,
						mWidthPx,
						y,
						colorTrait
				);

				canvas.drawText(
						MHz,
						baseXText,
						y + (isRoundMHz ? baseYText0 : baseYText5) - 6,
						isRoundMHz ? mFrequency0 : mFrequency5
				);
			} else {
				// Others dashes are short
				canvas.drawLine(
						mWidthPx - baseXShort,
						y,
						mWidthPx,
						y,
						colorTrait
				);
			}

			// If selected frequency equals current iterate, draw red line
			if (kHzCurrent == kHz) {
				canvas.drawLine(0, y, mWidthPx, y, mCurrentLine);
			}
		}

		canvas.rotate(90);
		canvas.translate(0, -height);
		super.onDraw(canvas);
	}

	@SuppressLint("ClickableViewAccessibility")
    @Override
	public boolean onTouchEvent(MotionEvent event) {
		event.setLocation(event.getY(), event.getX());

		return super.onTouchEvent(event);
	}

	/*
	/**
	 * List of stations, that will be draw
	 * /
	private Set<Integer> mStations;


	/**
	 * Update list of stations for draw lines on seek bar
	 * @param stations List of frequencies
	 * /
	public void notifyStationList(List<Integer> stations) {
		mStations = new HashSet<>(stations);
		invalidate();
	}
 */
}
