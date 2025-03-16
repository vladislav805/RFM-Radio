package com.vlad805.fmradio.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.vlad805.fmradio.R;

import java.util.Locale;

/**
 * vlad805 (c) 2018
 * Custom SeekBar
 */
public class FrequencyBarView extends View {
	private Paint mTrait;
	private Paint mFrequency0;
	private Paint mFrequency5;
	private Paint mRedLine;
	private Paint mStationLine;

	private GestureDetector gestureDetector;

	private int mValue = 87500;
	private int mValueMin = 87500;
	private int mValueMax = 108000;
	private int mSpacing = 100;

	/** Width of view */
	private int mWidth;
	/** Height of bar overall */
	private int mVirtualHeight;
	/** Middle red line Y */
	private int mVirtualMiddle;
	/** Interval between two dashed in bar */
	private float mDashIntervalLength;

	private float mOffsetY = 0;

	private boolean mIsUserGesture = false;

	private OnFrequencyChangedListener mOnFrequencyChangeListener;

	public interface OnFrequencyChangedListener {
		void onChanged(int kHz);
	}

	public FrequencyBarView(final Context context) {
		super(context);
		init();
	}

	public FrequencyBarView(final Context context, final AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public void setOnFrequencyChangeListener(OnFrequencyChangedListener listener) {
		mOnFrequencyChangeListener = listener;
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

		mRedLine = new Paint();
		mRedLine.setColor(Color.RED);
		mRedLine.setStrokeWidth(dpi * 2f);
		mRedLine.setStyle(Paint.Style.STROKE);

		mStationLine = new Paint();
		mStationLine.setColor(Color.YELLOW);
		mStationLine.setStrokeWidth(dpi);
		mStationLine.setStyle(Paint.Style.STROKE);

		mWidth = resources.getDimensionPixelOffset(R.dimen.seek_frequency_width);

		gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
			private long lastChange;

			@Override
			public boolean onDown(@NonNull MotionEvent e) {
				lastChange = System.currentTimeMillis();
				return true;
			}

			@Override
			public boolean onScroll(MotionEvent first, @NonNull MotionEvent move, float distanceX, float distanceY) {
				addOffsetY(distanceY);

				final long now = System.currentTimeMillis();

				if (lastChange + 1000 < now) {
					lastChange = now;
					callListener();
				}

				return true;
			}
		});
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		super.onSizeChanged(w, h, oldW, oldH);

		mVirtualHeight = (int) (h * 3.5);
		mVirtualMiddle = h / 2;
		mDashIntervalLength = mVirtualHeight / ((mValueMax - mValueMin) * 1f / mSpacing);
	}

	/**
	 * Change edges
	 * @param minValue Lower frequency in kHz
	 * @param maxValue Higher frequency in kHz
	 * @param spacing Spacing in kHz
	 */
	public void setMinMaxValue(final int minValue, final int maxValue, final int spacing) {
		mValueMin = minValue;
		mValueMax = maxValue;
		mSpacing = spacing;
	}

	private void addOffsetY(final float delta) {
		float value = mOffsetY + delta;

		if (value < 0) {
			value = 0f;
		} else 	if (value > mVirtualHeight) {
			value = mVirtualHeight;
		}

		setOffsetY(value);
	}

	private void setOffsetY(final float value) {
		mOffsetY = value;

		invalidate();
	}

	private void callListener() {
		if (mOnFrequencyChangeListener != null) {
			mOnFrequencyChangeListener.onChanged(getFrequencyByOffsetY(mOffsetY));
		}
	}

	/**
	 * Change current frequency
	 * @param kHz Frequency in kHz
	 */
	public void setFrequency(final int kHz) {
		mValue = kHz;

		if (!mIsUserGesture) {
			post(() -> setOffsetY(getOffsetYByFrequency(kHz)));
		}
	}

	public int getFrequency() {
		return mValue;
	}

	private float getOffsetYByFrequency(final int kHz) {
		final float relative = (float) (kHz - mValueMin);

		return relative / mSpacing * mDashIntervalLength;
	}

	private int getFrequencyByOffsetY(final float offset) {
		final int relative = Math.round((offset * mSpacing) / mDashIntervalLength / mSpacing);

		return mValueMin + relative * mSpacing;
	}

	@Override
	protected void onDraw(@NonNull final Canvas canvas) {
		// x-coordinate, end of short stick relative to the right
		final int baseXShort = 25;

		// x-coordinate, end of long dash relative to the right
		final int baseXLong = 60;

		// x-coordinate, position of label .0 relative to the right
		final int baseXText = mWidth - baseXLong - 5;

		final float baseYText0 = mFrequency0.getTextSize() / 2;
		final float baseYText5 = mFrequency5.getTextSize() / 2;

		canvas.save();
		canvas.translate(0, -mOffsetY + mVirtualMiddle);

		// For each 100 kHz ...
		for (int i = 0; i <= (mValueMax - mValueMin) / mSpacing; ++i) {

			// kHz frequency for current iteration
			final int kHz = mValueMin + i * mSpacing;

			// Y coordinate for current iteration
			final float y = mDashIntervalLength * i;

			// The presence of the current frequency in the list of stations
			final boolean hasStation = false; // mStations != null && mStations.contains(kHz);

			// Choose color for dash in depend of presence of the current frequency in the list of stations
			@SuppressWarnings("ConstantConditions") final Paint colorTrait = hasStation ? mStationLine : mTrait;

			final boolean isRoundMHz = kHz % 1000 == 0;

			// Label
			final String MHz = String.format(Locale.ENGLISH, isRoundMHz ? "%.0f" : "%.1f", kHz / 1000f);

			// If .0 MHz or .5 MHz
			if (kHz % 500 == 0) {
				// Draw long dash
				canvas.drawLine(
						mWidth - baseXLong,
						y,
						mWidth,
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
						mWidth - baseXShort,
						y,
						mWidth,
						y,
						colorTrait
				);
			}
		}

		canvas.restore();

		// Red line on middle
		canvas.drawLine(0, mVirtualMiddle, mWidth, mVirtualMiddle, mRedLine);

		super.onDraw(canvas);
	}

	@SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
		if (!isEnabled()) {
			return false;
		}

		if (event.getPointerCount() == 1) {
			switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN: {
					mIsUserGesture = true;
					break;
				}
				case MotionEvent.ACTION_UP: {
					mIsUserGesture = false;
					callListener();
					setFrequency(getFrequencyByOffsetY(mOffsetY));
					break;
				}
			}
		}

		return gestureDetector.onTouchEvent(event);
	}
}
