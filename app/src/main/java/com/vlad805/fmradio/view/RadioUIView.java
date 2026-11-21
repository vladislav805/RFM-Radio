package com.vlad805.fmradio.view;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.UtilsLocalization;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.preferences.BandUtils;

import net.grandcentrix.tray.AppPreferences;

/**
 * Material 3 tuner and RDS metadata display card.
 * Keeps fixed geometry for raw RadioText (3 lines) and uses a MaterialButtonToggleGroup
 * segmented button to switch between raw RT and structured RT+ (Artist, Title, Album).
 */
public class RadioUIView extends LinearLayout {
	private TextView mFrequencyView;
	private TextView mRdsPs;
	private TextView mRdsRt;
	private TextView mRdsPty;
	private TextView mRdsPtyLabel;
	private TextView mRdsPi;

	private View mRtPlusContainer;
	private TextView mRtPlusArtist;
	private TextView mRtPlusTitle;
	private TextView mRtPlusAlbum;

	private MaterialButtonToggleGroup mRtToggleGroup;
	private MaterialButton mBtnModeRt;
	private MaterialButton mBtnModeRtPlus;

	private boolean mShowRtPlusMode = false;
	private boolean mStatusShown;
	private String mLastPtyText = "";
	private final AppPreferences mPreferences;

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

	private void init() {
		LayoutInflater.from(getContext()).inflate(R.layout.frequency_info, this, true);

		mFrequencyView = findViewById(R.id.frequency_mhz);
		mRdsPs = findViewById(R.id.frequency_ps);
		mRdsRt = findViewById(R.id.frequency_rt);
		mRdsPty = findViewById(R.id.frequency_pty);
		mRdsPtyLabel = findViewById(R.id.frequency_pty_label);
		mRdsPi = findViewById(R.id.frequency_pi);

		mRtPlusContainer = findViewById(R.id.rt_plus_container);
		mRtPlusArtist = findViewById(R.id.rt_plus_artist_value);
		mRtPlusTitle = findViewById(R.id.rt_plus_title_value);
		mRtPlusAlbum = findViewById(R.id.rt_plus_album_value);

		mRtToggleGroup = findViewById(R.id.rt_mode_toggle_container);
		mBtnModeRt = findViewById(R.id.btn_mode_rt);
		mBtnModeRtPlus = findViewById(R.id.btn_mode_rt_plus);

		mRtToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
			if (!isChecked) {
				return;
			}
			setRtPlusMode(checkedId == R.id.btn_mode_rt_plus);
		});

		updateRtModeUi();
		clearMetadata();
	}

	public final void setRtPlusMode(final boolean showRtPlus) {
		if (mShowRtPlusMode == showRtPlus) {
			return;
		}
		mShowRtPlusMode = showRtPlus;
		final int targetId = showRtPlus ? R.id.btn_mode_rt_plus : R.id.btn_mode_rt;
		if (mRtToggleGroup.getCheckedButtonId() != targetId) {
			mRtToggleGroup.check(targetId);
		}
		updateRtModeUi();
	}

	private void updateRtModeUi() {
		if (mShowRtPlusMode) {
			mRdsRt.setVisibility(View.GONE);
			mRtPlusContainer.setVisibility(View.VISIBLE);
		} else {
			mRdsRt.setVisibility(View.VISIBLE);
			mRtPlusContainer.setVisibility(View.GONE);
		}
	}

	/**
	 * Called when frequency has changed externally
	 * @param kHz Frequency in kHz
	 */
	public final void setFrequency(final int kHz) {
		final int spacing = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);
		final boolean is50Khz = spacing == BandUtils.SPACING_50kHz;
		mFrequencyView.setTextSize(is50Khz ? 36f : 44f);
		mFrequencyView.setText(Utils.getMHz(kHz, is50Khz ? 2 : 1).trim());
	}

	public final void setRadioState(final RadioState state) {
		final String placeholder = getContext().getString(R.string.rds_placeholder);

		final String ps = state.getPs() != null ? state.getPs().trim() : "";
		mRdsPs.setText(ps.isEmpty() ? placeholder : ps);

		final String rtText = state.getRt() != null ? state.getRt().trim() : "";
		if (rtText.isEmpty()) {
			mRdsRt.setText(R.string.rds_rt_empty);
			mRdsRt.setTextColor(MaterialColors.getColor(
					this,
					com.google.android.material.R.attr.colorOnSurfaceVariant,
					ContextCompat.getColor(getContext(), R.color.text_secondary)
			));
		} else {
			mRdsRt.setText(rtText);
			mRdsRt.setTextColor(MaterialColors.getColor(
					this,
					com.google.android.material.R.attr.colorOnSurface,
					ContextCompat.getColor(getContext(), R.color.text_primary)
			));
		}

		final RtPlusInfo rtPlus = state.getRtPlus() != null ? state.getRtPlus() : RtPlusInfo.EMPTY;
		final String artist = rtPlus.getArtist().trim();
		final String title = rtPlus.getTitle().trim();
		final String album = rtPlus.getAlbum().trim();

		mRtPlusArtist.setText(artist.isEmpty() ? placeholder : artist);
		mRtPlusTitle.setText(title.isEmpty() ? placeholder : title);
		mRtPlusAlbum.setText(album.isEmpty() ? placeholder : album);

		// Indicate RT+ availability via label suffix without overriding Material 3 button colors
		final String rtPlusLabel = getContext().getString(R.string.rds_rt_plus_label);
		mBtnModeRtPlus.setText(!rtPlus.isEmpty() ? rtPlusLabel + " •" : rtPlusLabel);

		final int region = mPreferences.getInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
		final String ptyName = UtilsLocalization.getProgramType(state.getPty(), region);
		mLastPtyText = (ptyName == null || ptyName.trim().isEmpty()) ? placeholder : ptyName;

		if (!mStatusShown) {
			mRdsPtyLabel.setText(R.string.rds_pty_prefix);
			mRdsPty.setText(mLastPtyText);
		}

		final String pi = state.getPi() != null ? state.getPi().trim() : "";
		final String country = state.getCountry() != null ? state.getCountry().trim() : "";
		if (pi.isEmpty()) {
			mRdsPi.setText(placeholder);
		} else {
			mRdsPi.setText(country.isEmpty() ? pi : country + " · " + pi);
		}
	}

	public final void showStatus(@StringRes final int stringRes) {
		mStatusShown = true;
		mRdsPtyLabel.setText("SYS");
		mRdsPty.setText(stringRes);
	}

	public final void hideStatus() {
		mStatusShown = false;
		mRdsPtyLabel.setText(R.string.rds_pty_prefix);
		final String placeholder = getContext().getString(R.string.rds_placeholder);
		mRdsPty.setText(mLastPtyText.isEmpty() ? placeholder : mLastPtyText);
	}

	public final void clearMetadata() {
		final String placeholder = getContext().getString(R.string.rds_placeholder);
		mRdsPs.setText(placeholder);
		mRdsPi.setText(placeholder);
		mRdsRt.setText(R.string.rds_rt_empty);
		mRdsRt.setTextColor(MaterialColors.getColor(
				this,
				com.google.android.material.R.attr.colorOnSurfaceVariant,
				ContextCompat.getColor(getContext(), R.color.text_secondary)
		));
		mRtPlusArtist.setText(placeholder);
		mRtPlusTitle.setText(placeholder);
		mRtPlusAlbum.setText(placeholder);
		mBtnModeRtPlus.setText(R.string.rds_rt_plus_label);
		mLastPtyText = placeholder;
		if (!mStatusShown) {
			mRdsPtyLabel.setText(R.string.rds_pty_prefix);
			mRdsPty.setText(placeholder);
		}
	}
}
