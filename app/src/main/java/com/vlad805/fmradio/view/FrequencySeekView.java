package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.SeekBar;

/**
 * vlad805 (c) 2018
 */
public class FrequencySeekView extends SeekBar {

	private Paint mTrait;
	private Paint mFrequency0;
	private Paint mFrequency5;
	private Paint mCurrentLine;

	private float mValueMin = 0;
	private float mValueMax = 180;

	public FrequencySeekView(Context context) {
		super(context);
		init();
	}

	public FrequencySeekView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	private void init() {
		float dpi = getResources().getDisplayMetrics().density;

		mTrait = new Paint();
		mTrait.setColor(Color.GRAY);
		mTrait.setStrokeWidth(dpi * 1f);
		mTrait.setStyle(Paint.Style.STROKE);

		mFrequency0 = new Paint();
		mFrequency0.setColor(Color.WHITE);
		mFrequency0.setTextSize(sp2px(22));
		mFrequency0.setTextAlign(Paint.Align.CENTER);
		mFrequency0.setAntiAlias(true);

		mFrequency5 = new Paint();
		mFrequency5.setColor(Color.LTGRAY);
		mFrequency5.setTextSize(sp2px(12));
		mFrequency5.setTextAlign(Paint.Align.CENTER);
		mFrequency5.setColor(Color.LTGRAY);
		mFrequency5.setAntiAlias(true);

		mCurrentLine = new Paint();
		mCurrentLine.setColor(Color.RED);
		mCurrentLine.setStrokeWidth(dpi * 2f);
		mCurrentLine.setStyle(Paint.Style.STROKE);

		invalidate();
	}

	private void setMaxValue(float maxValue) {
		mValueMax = maxValue;
	}

	private void setMinValue(float minValue) {
		mValueMin = minValue;
	}

	public FrequencySeekView setMinMaxValue(float minValue, float maxValue) {
		setMinValue(minValue);
		setMaxValue(maxValue);
		setMax((int) (mValueMax - mValueMin));
		return this;
	}

	public double getValue() {
		return mValueMin + getProgress();
	}

	private int sp2px(float spValue) {
		final float fontScale = getResources().getDisplayMetrics().scaledDensity;
		return (int) (spValue * fontScale + 0.5f);
	}

	@Override
	public synchronized void setProgress(int progress) {
		progress -= mValueMin;
		super.setProgress(progress);
	}

	public int fixProgress(int progress) {
		progress += mValueMin;
		return progress;
	}

	@Override
	public synchronized int getProgress() {
		int val = super.getProgress();
		val += mValueMin;
		return val;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		float paddingTop = 0; //30;

		int viewWidth = getWidth() - getPaddingLeft() - getPaddingRight();

		int deltaX = getPaddingLeft();

		float viewInterval = (float) viewWidth / (mValueMax - mValueMin);

		float baseX;

		float baseYShort = paddingTop + 25; // конец короткой палочки
		float baseYLong = paddingTop + 60; // конец длинной палочки
		float baseYText0 = baseYLong + 5 + mFrequency0.getTextSize(); // MHz .0
		float baseYText5 = baseYLong + 5 + mFrequency5.getTextSize(); // MHz .5
		float MHzFloat;
		double currentValue = getValue() - mValueMin;
		float now = -1f;

		canvas.drawLine(deltaX, paddingTop, getWidth() - getPaddingRight(), paddingTop, mTrait);

		for (int i = 0; i < mValueMax - mValueMin + 1; i++) {
			baseX = deltaX + viewInterval * i; // ось X для всех
			MHzFloat = i + mValueMin;

			if (MHzFloat == currentValue) {
				now = baseX;
			}

			// Если .0 или .5
			if (i % 5 == 0) {
				String MHz = String.valueOf(MHzFloat / 10);

				// Длинная палочка
				canvas.drawLine(baseX, paddingTop, baseX, baseYLong, mTrait);

				if ((MHzFloat % 10) > 0) { // Если .5, мелкий текст
					canvas.drawText(MHz, baseX, baseYText5, mFrequency5);
				} else { // Если .0, крупный текст
					canvas.drawText(MHz, baseX, baseYText0, mFrequency0);
				}
			} else { // Остальное с мелкой палочкой
				canvas.drawLine(baseX, paddingTop, baseX, baseYShort, mTrait);
			}
		}

		if (now != -1f) {
			canvas.drawLine(now, 0, now, getHeight(), mCurrentLine);
		}

		super.onDraw(canvas);
	}
}
