package com.vlad805.fmradio.view;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
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
	private TextView mRdsPi;
	private FrequencyBarView mSeek;
	private RadioController mRadioController;
	private BandUtils.BandLimit mBandLimits;
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
		mSeek = findViewById(R.id.frequency_seek);
		mRdsPty = findViewById(R.id.frequency_pty);
		mRdsPi = findViewById(R.id.frequency_pi);

		final Typeface font = Typeface.createFromAsset(getContext().getAssets(), "fonts/digital-number.ttf");
		mFrequencyView.setTypeface(font);

		reloadPreferences();
		mkHz = mBandLimits.lower;
	}

	private void reloadPreferences() {
		final int regionPref = mPreferences.getInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
		final int spacingPref = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

		mBandLimits = BandUtils.getBandLimit(regionPref);
        final int spacing = BandUtils.getSpacing(spacingPref);

		mSeek.setMinMaxValue(mBandLimits.lower, mBandLimits.upper, spacing);
		mSeek.setOnFrequencyChangeListener(mOnFrequencyChanged);
	}

	/**
	 * Calls when frequency has changed externally
	 * @param kHz Frequency in kHz
	 */
	public final void setFrequency(int kHz) {
		mkHz = kHz;
		final int spacing = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

		mFrequencyView.setText(Utils.getMHz(kHz, spacing == BandUtils.SPACING_50kHz ? 2 : 1));

		mSeek.setFrequency(kHz);
	}

	public final void setRadioState(final RadioState state) {
		mRdsPs.setText(state.getPs());
		mRdsRt.setText(state.getRt());
		mRdsPty.setText(UtilsLocalization.getProgramType(state.getPty()));
		mRdsPi.setText(state.getPi() != null ? state.getPi() : "");
	}

	private final FrequencyBarView.OnFrequencyChangedListener mOnFrequencyChanged = new FrequencyBarView.OnFrequencyChangedListener() {
		@Override
		public void onChanged(final int kHz) {
			if (mkHz == kHz) {
				return;
			}

			mRadioController.setFrequency(kHz);

			setFrequency(kHz);
		}
	};

	public void setRadioController(final RadioController controller) {
		mRadioController = controller;
	}
}
