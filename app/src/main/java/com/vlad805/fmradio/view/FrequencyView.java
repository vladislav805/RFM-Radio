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

import java.util.Locale;

/**
 * vlad805 (c) 2019
 */
public class FrequencyView extends LinearLayout {

	private TextViewWithSupportReflection mFrequency;
	private ImageView mShadow;
	private TextView mRdsPs;
	private FrequencySeekView mSeek;

	private Typeface mDigitalFont;

	private OnFrequencyChanged mListener;

	private int mkHz = 87500;

	public interface OnFrequencyChanged {
		void onChanged(int kHz);
	}

	public FrequencyView(Context context) {
		super(context);

		init();
	}

	public FrequencyView(Context context, AttributeSet attrs) {
		super(context, attrs);

		init();
	}

	private void init() {
		LayoutInflater.from(getContext()).inflate(R.layout.frequency_info, this, true);

		mDigitalFont = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");

		mFrequency = findViewById(R.id.frequency_mhz);
		mShadow = findViewById(R.id.frequency_mhz_reflection);
		mRdsPs = findViewById(R.id.frequency_ps);
		mSeek = findViewById(R.id.frequency_seek);

		mFrequency.setTypeface(mDigitalFont);

		mSeek.setMinMaxValue(875, 1080);
		mSeek.setOnSeekBarChangeListener(mOnSeekFrequencyChanged);
	}

	public void setOnFrequencyChangedListener(OnFrequencyChanged listener) {
		mListener = listener;
	}

	public final void setFrequency(int kHz) {
		mkHz = kHz;
		mFrequency.setText(getMHz(kHz));

		mSeek.setProgress(kHz / 100);

		post(new Runnable() {
			@Override
			public void run() {
				mShadow.setImageBitmap(mFrequency.getReflection(.9f));
			}
		});
	}

	public final void setRdsPs(String ps) {
		mRdsPs.setText(ps);
	}

	private String getMHz(int kHz) {
		return String.format(Locale.ENGLISH, "%5.1f", kHz / 1000.);
	}

	private void notifyUpdate(int kHz) {
		if (mkHz == kHz) {
			return;
		}

		if (mListener != null) {
			mListener.onChanged(kHz);
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

			current = mSeek.fixProgress(progress) * 100;

			notifyUpdate(current);
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) { }

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			int d = current;

			notifyUpdate(d);
		}
	};
}
