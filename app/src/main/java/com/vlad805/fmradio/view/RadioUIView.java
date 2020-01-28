package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.*;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.RadioController;

/**
 * vlad805 (c) 2019
 */
public class RadioUIView extends LinearLayout {
	private TextViewWithSupportReflection mFrequencyView;
	private ImageView mReflection;
	private TextView mRdsPs;
	private TextView mRdsRt;
	private HorizontalScrollView mSeekWrap;
	private FrequencySeekView mSeek;
	private RadioController mRadioController;

	/**
	 * Current frequency
	 */
	private int mkHz = 87500;

	/**
	 * Settings
	 */
	private static final int BAND_LOW = 87500;
	private static final int BAND_HIGH = 108000;
	private static final int BAND_STEP = 100;

	public RadioUIView(Context context) {
		super(context);

		init();
	}

	public RadioUIView(Context context, AttributeSet attrs) {
		super(context, attrs);

		init();
	}

	private void init() {
		LayoutInflater.from(getContext()).inflate(R.layout.frequency_info, this, true);

		mFrequencyView = findViewById(R.id.frequency_mhz);
		mReflection = findViewById(R.id.frequency_mhz_reflection);
		mRdsPs = findViewById(R.id.frequency_ps);
		mRdsRt = findViewById(R.id.frequency_rt);
		mSeekWrap = findViewById(R.id.frequency_seek_wrap);
		mSeek = findViewById(R.id.frequency_seek);

		Typeface font = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");
		mFrequencyView.setTypeface(font);

		mSeek.setMinMaxValue(BAND_LOW, BAND_HIGH, BAND_STEP);
		mSeek.setOnSeekBarChangeListener(mOnSeekFrequencyChanged);
	}

	public void hideReflection() {
		mReflection.setVisibility(GONE);
	}

	/**
	 * Calls when frequency has changed externally
	 * @param kHz Frequency in kHz
	 */
	public final void setFrequency(int kHz) {
		mkHz = kHz;
		mFrequencyView.setText(Utils.getMHz(kHz));

		mSeek.setProgress(kHz);

		post(() -> mReflection.setImageBitmap(mFrequencyView.getReflection(.9f)));

		scrollSeekBar();
	}

	public final void setRdsPs(String ps) {
		mRdsPs.setText(ps);
	}

	public final void setRdsRt(String rt) {
		mRdsRt.setText(rt);
	}

	private void onUserClickOnFrequency(int kHz) {
		if (mkHz == kHz) {
			return;
		}

		mRadioController.setFrequency(getContext(), kHz);

		setFrequency(kHz);
	}

	private SeekBar.OnSeekBarChangeListener mOnSeekFrequencyChanged = new SeekBar.OnSeekBarChangeListener() {

		private int current;

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (!fromUser) {
				return;
			}

			int curr = seekBar.getProgress();

			/*
			 * Android 5.1 (Sony Xperia L at least) progress contains value from
			 * seekBar.getProgress(), that was already fixed
			 */
			if (curr > BAND_HIGH) {
				curr /= 1000;
			}

			current = curr;

			onUserClickOnFrequency(current);
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) { }

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			onUserClickOnFrequency(current);
		}
	};

	public void setRadioController(final RadioController controller) {
		mRadioController = controller;
	}

	/**
	 * Center the scrollview for seekbar so that the red line is centered
	 */
	private void scrollSeekBar() {
		final int deltaPadding = mSeek.getPaddingLeft() + mSeek.getPaddingRight();
		final int bandLength = (BAND_HIGH - BAND_LOW) / BAND_STEP;
		final int ticksFromStart = (mkHz - BAND_LOW) / BAND_STEP;

		final int viewWidth = mSeek.getWidth() - deltaPadding;
		final float viewInterval = viewWidth * 1f / bandLength;

		final int halfScreen = mSeekWrap.getWidth() / 2;

		final int x = (int) (viewInterval * ticksFromStart - halfScreen + mSeek.getPaddingLeft());
		mSeekWrap.smoothScrollTo(x, 0);
	}
}
