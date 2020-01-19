package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;

/**
 * vlad805 (c) 2019
 */
public class RadioUIView extends LinearLayout {
	private TextViewWithSupportReflection mFrequencyView;
	private ImageView mShadow;
	private TextView mRdsPs;
	private TextView mRdsRt;
	private FrequencySeekView mSeek;

	private OnUserFrequencyChange mListener;

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

	/**
	 * Callback for external code
	 * Calls when user select frequency by click on seek bar
	 */
	public interface OnUserFrequencyChange {
		void onUserChangeFrequency(int kHz);
	}

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
		mShadow = findViewById(R.id.frequency_mhz_reflection);
		mRdsPs = findViewById(R.id.frequency_ps);
		mRdsRt = findViewById(R.id.frequency_rt);
		mSeek = findViewById(R.id.frequency_seek);

		Typeface font = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");
		mFrequencyView.setTypeface(font);

		mSeek.setMinMaxValue(BAND_LOW, BAND_HIGH, BAND_STEP);
		mSeek.setOnSeekBarChangeListener(mOnSeekFrequencyChanged);
	}


	public void setOnFrequencyChangedListener(OnUserFrequencyChange listener) {
		mListener = listener;
	}

	public final void setFrequency(int kHz) {
		mkHz = kHz;
		mFrequencyView.setText(Utils.getMHz(kHz));

		mSeek.setProgress(kHz);

		post(() -> mShadow.setImageBitmap(mFrequencyView.getReflection(.9f)));
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

		if (mListener != null) {
			mListener.onUserChangeFrequency(kHz);
		}

		setFrequency(kHz);
	}

	private SeekBar.OnSeekBarChangeListener mOnSeekFrequencyChanged = new SeekBar.OnSeekBarChangeListener() {

		private int current;

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (!fromUser) {
				return;
			}

			current = mSeek.fixProgress(progress);

			onUserClickOnFrequency(current);
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) { }

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			onUserClickOnFrequency(current);
		}
	};
}
