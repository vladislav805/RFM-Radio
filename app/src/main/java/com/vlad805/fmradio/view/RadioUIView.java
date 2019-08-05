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
import com.vlad805.fmradio.db.IStation;

import java.util.List;
import java.util.Locale;

/**
 * vlad805 (c) 2019
 */
public class RadioUIView extends LinearLayout {

	private TextViewWithSupportReflection mFrequency;
	private ImageView mShadow;
	private TextView mRdsPs;
	private TextView mRdsRt;
	private FrequencySeekView mSeek;

	private OnFrequencyChanged mListener;

	private int mkHz = 87500;

	private static final int BAND_LOW = 87500;
	private static final int BAND_HIGH = 108000;
	private static final int BAND_STEP = 100;

	public interface OnFrequencyChanged {
		void onChanged(int kHz);
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

		Typeface font = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");

		mFrequency = findViewById(R.id.frequency_mhz);
		mShadow = findViewById(R.id.frequency_mhz_reflection);
		mRdsPs = findViewById(R.id.frequency_ps);
		mRdsRt = findViewById(R.id.frequency_rt);
		mSeek = findViewById(R.id.frequency_seek);

		mFrequency.setTypeface(font);

		mSeek.setMinMaxValue(BAND_LOW, BAND_HIGH, BAND_STEP);
		mSeek.setOnSeekBarChangeListener(mOnSeekFrequencyChanged);
	}

	public void setOnFrequencyChangedListener(OnFrequencyChanged listener) {
		mListener = listener;
	}

	public final void setFrequency(int kHz) {
		mkHz = kHz;
		mFrequency.setText(getMHz(kHz));

		mSeek.setProgress(kHz);

		post(() -> mShadow.setImageBitmap(mFrequency.getReflection(.9f)));
	}

	public final void setRdsPs(String ps) {
		mRdsPs.setText(ps);
	}

	public void setRdsRt(String rt) {
		mRdsRt.setText(rt);
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

	public void notifyStationsLists(List<IStation> list) {
		mSeek.notifyStationList(list);
	}

	private SeekBar.OnSeekBarChangeListener mOnSeekFrequencyChanged = new SeekBar.OnSeekBarChangeListener() {

		private int current;

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
			if (!fromUser) {
				return;
			}

			current = mSeek.fixProgress(progress);

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
