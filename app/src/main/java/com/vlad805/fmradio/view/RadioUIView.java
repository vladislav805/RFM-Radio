package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.UtilsLocalization;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.preferences.BandUtils;
import net.grandcentrix.tray.AppPreferences;

/**
 * vlad805 (c) 2019
 */
public class RadioUIView extends LinearLayout {
	private TextViewWithReflection mFrequencyView;
	private TextView mRdsPs;
	private TextView mRdsRt;
	private TextView mRdsPty;
	private HorizontalScrollView mSeekWrap;
	private FrequencySeekView mSeek;
	private RadioController mRadioController;
	private BandUtils.BandLimit mBandLimits;
	private int mSpacing;
	private final AppPreferences mPreferences;

	/**
	 * Current frequency
	 */
	private int mkHz;

	public RadioUIView(final Context context) {
		super(context);

		mPreferences = new AppPreferences(getContext());

		init();
	}

	public RadioUIView(final Context context, final AttributeSet attrs) {
		super(context, attrs);

		mPreferences = new AppPreferences(getContext());

		init();
	}

	@Override
	protected void onWindowVisibilityChanged(int visibility) {
		super.onWindowVisibilityChanged(visibility);

		this.reloadPreferences();
	}

	private void init() {
		LayoutInflater.from(getContext()).inflate(R.layout.frequency_info, this, true);

		mFrequencyView = findViewById(R.id.frequency_mhz);
		mRdsPs = findViewById(R.id.frequency_ps);
		mRdsRt = findViewById(R.id.frequency_rt);
		mSeekWrap = findViewById(R.id.frequency_seek_wrap);
		mSeek = findViewById(R.id.frequency_seek);
		mRdsPty = findViewById(R.id.program_type_value);

		final Typeface font = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");
		mFrequencyView.setTypeface(font);

		reloadPreferences();
		mkHz = mBandLimits.lower;
	}

	private void reloadPreferences() {
		final int regionPref = mPreferences.getInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
		final int spacingPref = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

		mBandLimits = BandUtils.getBandLimit(regionPref);
		mSpacing = BandUtils.getSpacing(spacingPref);

		mSeek.setMinMaxValue(mBandLimits.lower, mBandLimits.upper, mSpacing);
		mSeek.setOnSeekBarChangeListener(mOnSeekFrequencyChanged);

		final float seekWidthDp = (mBandLimits.upper - mBandLimits.lower) / 10.67f;
		final int seekWidthPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, seekWidthDp, getResources().getDisplayMetrics());

		final ViewGroup.LayoutParams lp = mSeek.getLayoutParams();
		lp.width = seekWidthPx;
		mSeek.setLayoutParams(lp);
	}

	/**
	 * Calls when frequency has changed externally
	 * @param kHz Frequency in kHz
	 */
	public final void setFrequency(int kHz) {
		mkHz = kHz;
		final int spacing = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

		mFrequencyView.setText(Utils.getMHz(kHz, spacing == BandUtils.SPACING_50kHz ? 2 : 1));

		mSeek.setProgress(kHz);

		scrollSeekBar();
	}

	public final void setRadioState(final RadioState state) {
		mRdsPs.setText(state.getPs());
		mRdsRt.setText(state.getRt());
		mRdsPty.setText(UtilsLocalization.getProgramType(state.getPty()));
	}

	private void onUserClickOnFrequency(final int kHz) {
		if (mkHz == kHz) {
			return;
		}

		mRadioController.setFrequency(kHz);

		setFrequency(kHz);
	}

	private final SeekBar.OnSeekBarChangeListener mOnSeekFrequencyChanged = new SeekBar.OnSeekBarChangeListener() {

		private int current;

		@Override
		public void onProgressChanged(final SeekBar seekBar, final int progress, final boolean fromUser) {
			if (!fromUser) {
				return;
			}

			int curr = seekBar.getProgress();

			/*
			 * Android 5.1 (Sony Xperia L at least) progress contains value from
			 * seekBar.getProgress(), that was already fixed
			 */
			if (curr > mBandLimits.upper) {
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
		final int bandLength = (mBandLimits.upper - mBandLimits.lower) / mSpacing;
		final int ticksFromStart = (mkHz - mBandLimits.lower) / mSpacing;

		final int viewWidth = mSeek.getWidth() - deltaPadding;
		final float viewInterval = viewWidth * 1f / bandLength;

		final int halfScreen = mSeekWrap.getWidth() / 2;

		final int x = (int) (viewInterval * ticksFromStart - halfScreen + mSeek.getPaddingLeft());
		mSeekWrap.smoothScrollTo(x, 0);
	}
}
