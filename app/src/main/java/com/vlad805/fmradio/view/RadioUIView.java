package com.vlad805.fmradio.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.UtilsLocalization;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.models.RtPlusFields;
import com.vlad805.fmradio.models.RtPlusInfo;
import com.vlad805.fmradio.preferences.BandUtils;

import java.util.Map;

/**
 * UI binder/controller for the tuner frequency, RDS metadata, and RT+ overlay.
 */
public final class RadioUIView {
	private final Context mContext;
	private final Storage mStorage;
	private final TextView mFrequencyView;
	private final TextView mRdsPs;
	private final TextView mRdsRt;
	private final TextView mRdsPty;
	private final TextView mRdsTp;
	private final TextView mRdsPtyLabel;
	private final TextView mRdsPi;

	private final ViewGroup mRtCard;
	private final View mRtPlusOverlay;
	private final TableLayout mRtPlusFields;
	private final Map<String, String> mRtPlusLabels;
	private RtPlusInfo mDisplayedRtPlus = RtPlusInfo.EMPTY;

	private final MaterialCardView mBtnToggleRtPlus;

	private boolean mShowRtPlusMode = false;
	private boolean mStatusShown;
	private String mLastPtyText = "";
	private String mLastDisplayedPty = "";
	private String mLastPtyLabel = "";
	private String mLastPsText = "";
	private String mLastPiText = "";
	private String mLastRtText = "";
	private boolean mShowingEmptyRt = true;
	private ValueAnimator mRtAppendAnimator;

	public RadioUIView(final View root) {
		mContext = root.getContext();
		mStorage = Storage.get(mContext);

		mFrequencyView = root.findViewById(R.id.frequency_mhz);
		mRdsPs = root.findViewById(R.id.frequency_ps);
		mRdsRt = root.findViewById(R.id.frequency_rt);
		mRdsPty = root.findViewById(R.id.frequency_pty);
		mRdsTp = root.findViewById(R.id.frequency_tp);
		mRdsPtyLabel = root.findViewById(R.id.frequency_pty_label);
		mRdsPi = root.findViewById(R.id.frequency_pi);

		mRtCard = root.findViewById(R.id.rt_card);
		mRtPlusOverlay = root.findViewById(R.id.rt_plus_overlay);
		mRtPlusFields = root.findViewById(R.id.rt_plus_fields);
		mRtPlusLabels = RtPlusFields.localizedLabels(mContext.getResources());

		mBtnToggleRtPlus = root.findViewById(R.id.btn_toggle_rt_plus);
		mBtnToggleRtPlus.setOnClickListener(v -> setRtPlusMode(!mShowRtPlusMode));

		final View closeButton = root.findViewById(R.id.btn_close_rt_plus);
		if (closeButton != null) {
			closeButton.setOnClickListener(v -> setRtPlusMode(false));
		}

		updateRtModeUi();
		clearMetadata();
	}

	private Context getContext() {
		return mContext;
	}

	public final void setRtPlusMode(final boolean showRtPlus) {
		if (mShowRtPlusMode == showRtPlus) {
			return;
		}
		mShowRtPlusMode = showRtPlus;
		updateRtModeUi();
	}

	private void updateRtModeUi() {
		if (mRtPlusOverlay == null) {
			return;
		}
		final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
		if (mShowRtPlusMode) {
			mRtPlusOverlay.animate().cancel();
			mRtPlusOverlay.setVisibility(View.VISIBLE);
			if (mRtPlusOverlay.isLaidOut()) {
				mRtPlusOverlay.setPivotX(mRtPlusOverlay.getWidth() * 0.9f);
				mRtPlusOverlay.setPivotY(0f);
				mRtPlusOverlay.setScaleX(0.82f);
				mRtPlusOverlay.setScaleY(0.35f);
				mRtPlusOverlay.setAlpha(0f);
				mRtPlusOverlay.animate()
						.scaleX(1f)
						.scaleY(1f)
						.alpha(1f)
						.setDuration(240L)
						.setInterpolator(interpolator)
						.withEndAction(null)
						.start();
			} else {
				mRtPlusOverlay.post(() -> {
					mRtPlusOverlay.setPivotX(mRtPlusOverlay.getWidth() * 0.9f);
					mRtPlusOverlay.setPivotY(0f);
					mRtPlusOverlay.setScaleX(0.82f);
					mRtPlusOverlay.setScaleY(0.35f);
					mRtPlusOverlay.setAlpha(0f);
					mRtPlusOverlay.animate()
							.scaleX(1f)
							.scaleY(1f)
							.alpha(1f)
							.setDuration(240L)
							.setInterpolator(interpolator)
							.withEndAction(null)
							.start();
				});
			}
		} else {
			mRtPlusOverlay.animate().cancel();
			if (mRtPlusOverlay.getVisibility() == View.VISIBLE && mRtPlusOverlay.isLaidOut()) {
				mRtPlusOverlay.setPivotX(mRtPlusOverlay.getWidth() * 0.9f);
				mRtPlusOverlay.setPivotY(0f);
				mRtPlusOverlay.animate()
						.scaleX(0.82f)
						.scaleY(0.35f)
						.alpha(0f)
						.setDuration(190L)
						.setInterpolator(interpolator)
						.withEndAction(() -> {
							mRtPlusOverlay.setVisibility(View.GONE);
							mRtPlusOverlay.setScaleX(1f);
							mRtPlusOverlay.setScaleY(1f);
							mRtPlusOverlay.setAlpha(1f);
						})
						.start();
			} else {
				mRtPlusOverlay.setVisibility(View.GONE);
			}
		}
	}

	/**
	 * Called when frequency has changed externally
	 * @param kHz Frequency in kHz
	 */
	public final void setFrequency(final int kHz) {
		final int spacing = mStorage.getSpacing();
		final boolean is50Khz = spacing == BandUtils.SPACING_50kHz;
		mFrequencyView.setTextSize(is50Khz ? 36f : 44f);
		mFrequencyView.setText(Utils.getMHz(kHz, is50Khz ? 2 : 1).trim());
	}

	public final void setRadioState(final RadioState state) {
		mRdsTp.setVisibility(state.hasTrafficProgramme() ? View.VISIBLE : View.GONE);
		final String placeholder = getContext().getString(R.string.rds_placeholder);

		final String ps = state.getPs() != null ? state.getPs().trim() : "";
		final String nextPs = ps.isEmpty() ? placeholder : ps;
		if (!nextPs.equals(mLastPsText)) {
			final boolean isContinuation = !mLastPsText.isEmpty()
					&& !mLastPsText.equals(placeholder)
					&& nextPs.startsWith(mLastPsText);
			mLastPsText = nextPs;
			if (isContinuation) {
				mRdsPs.setText(nextPs);
			} else {
				setTextSmooth(mRdsPs, nextPs);
			}
		}

		final String rtText = state.getRt() != null ? state.getRt().trim() : "";
		if (rtText.isEmpty()) {
			mLastRtText = "";
			final int mutedColor = MaterialColors.getColor(
					mRdsRt,
					com.google.android.material.R.attr.colorOnSurfaceVariant,
					ContextCompat.getColor(getContext(), R.color.text_secondary)
			);
			mRdsRt.setTextColor(mutedColor);
			if (!mShowingEmptyRt) {
				mShowingEmptyRt = true;
				cancelRtAppendAnimator();
				setTextSmooth(mRdsRt, getContext().getString(R.string.rds_rt_empty));
			}
		} else {
			mShowingEmptyRt = false;
			final int primaryTextColor = MaterialColors.getColor(
					mRdsRt,
					com.google.android.material.R.attr.colorOnSurface,
					ContextCompat.getColor(getContext(), R.color.text_primary)
			);
			mRdsRt.setTextColor(primaryTextColor);
			if (!rtText.equals(mLastRtText)) {
				final String prevRt = mLastRtText;
				mLastRtText = rtText;
				if (!prevRt.isEmpty() && rtText.startsWith(prevRt)) {
					animateAppendedRt(rtText, prevRt.length(), primaryTextColor);
				} else {
					cancelRtAppendAnimator();
					setTextSmooth(mRdsRt, rtText);
				}
			}
		}

		final RtPlusInfo rtPlus = state.getRtPlus() != null ? state.getRtPlus() : RtPlusInfo.EMPTY;
		showRtPlusFields(rtPlus);

		final boolean hasRtPlus = !rtPlus.isEmpty();
		if (mBtnToggleRtPlus != null) {
			final int nextVisibility = hasRtPlus ? View.VISIBLE : View.GONE;

			if (mBtnToggleRtPlus.getVisibility() != nextVisibility) {
				if (mRtCard != null && mRtCard.isLaidOut()) {
					final TransitionSet transition = new TransitionSet()
							.addTransition(new ChangeBounds())
							.addTransition(new Fade())
							.setDuration(180L)
							.setInterpolator(new FastOutSlowInInterpolator());
					TransitionManager.beginDelayedTransition(mRtCard, transition);
				}

				mBtnToggleRtPlus.setVisibility(nextVisibility);
			}
		}

		if (!hasRtPlus && mShowRtPlusMode) {
			setRtPlusMode(false);
		}

		final int region = mStorage.getRegion();
		final String ptyName = UtilsLocalization.getProgramType(state.getPty(), region);
		mLastPtyText = (ptyName == null || ptyName.trim().isEmpty()) ? placeholder : ptyName;

		if (!mStatusShown) {
			updatePtyDisplay(getContext().getString(R.string.rds_pty_prefix), mLastPtyText);
		}

		final String pi = state.getPi() != null ? state.getPi().trim() : "";
		final String country = state.getCountry() != null ? state.getCountry().trim() : "";
		final String nextPi = pi.isEmpty() ? placeholder : (country.isEmpty() ? pi : country + " · " + pi);
		if (!nextPi.equals(mLastPiText)) {
			mLastPiText = nextPi;
			setTextSmooth(mRdsPi, nextPi);
		}
	}

	private void showRtPlusFields(final RtPlusInfo info) {
		if (info.equals(mDisplayedRtPlus)) {
			return;
		}

		mDisplayedRtPlus = info;
		mRtPlusFields.removeAllViews();
		final LayoutInflater inflater = LayoutInflater.from(mContext);

		for (Map.Entry<String, String> field : info.getFields().entrySet()) {
			final View row = inflater.inflate(R.layout.rt_plus_field, mRtPlusFields, false);
			final TextView label = row.findViewById(R.id.rt_plus_field_label);
			final TextView value = row.findViewById(R.id.rt_plus_field_value);
			label.setText(mRtPlusLabels.get(field.getKey()));
			value.setText(field.getValue());
			mRtPlusFields.addView(row);
		}
	}

	public final void showStatus(@StringRes final int stringRes) {
		mStatusShown = true;
		updatePtyDisplay("SYS", getContext().getString(stringRes));
	}

	public final void hideStatus() {
		mStatusShown = false;
		final String placeholder = getContext().getString(R.string.rds_placeholder);
		updatePtyDisplay(
				getContext().getString(R.string.rds_pty_prefix),
				mLastPtyText.isEmpty() ? placeholder : mLastPtyText
		);
	}

	private void updatePtyDisplay(final String label, final String value) {
		if (!label.equals(mLastPtyLabel)) {
			mLastPtyLabel = label;
			setTextSmooth(mRdsPtyLabel, label);
		}
		if (!value.equals(mLastDisplayedPty)) {
			mLastDisplayedPty = value;
			setTextSmooth(mRdsPty, value);
		}
	}

	private void animateAppendedRt(final String fullText, final int oldLen, final int textColor) {
		cancelRtAppendAnimator();
		if (!mRdsRt.isLaidOut() || oldLen >= fullText.length()) {
			mRdsRt.setText(fullText);
			return;
		}
		mRdsRt.animate().cancel();
		mRdsRt.setAlpha(1f);

		final int targetAlpha = Color.alpha(textColor);
		final int rgb = textColor & 0x00FFFFFF;
		mRtAppendAnimator = ValueAnimator.ofInt(30, targetAlpha);
		mRtAppendAnimator.setDuration(220L);
		mRtAppendAnimator.addUpdateListener(animation -> {
			final int alpha = (int) animation.getAnimatedValue();
			final SpannableStringBuilder ssb = new SpannableStringBuilder(fullText);
			ssb.setSpan(
					new ForegroundColorSpan((alpha << 24) | rgb),
					oldLen,
					fullText.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
			);
			mRdsRt.setText(ssb);
		});
		mRtAppendAnimator.start();
	}

	private void cancelRtAppendAnimator() {
		if (mRtAppendAnimator != null) {
			mRtAppendAnimator.cancel();
			mRtAppendAnimator = null;
		}
	}

	public void release() {
		cancelRtAppendAnimator();
	}

	private void setTextSmooth(final TextView view, final String text) {
		view.setText(text);
		if (!view.isLaidOut()) {
			return;
		}
		view.animate().cancel();
		view.setAlpha(0.35f);
		view.animate()
				.alpha(1f)
				.setDuration(160L)
				.start();
	}

	public final void clearMetadata() {
		mRdsTp.setVisibility(View.GONE);
		final String placeholder = getContext().getString(R.string.rds_placeholder);
		cancelRtAppendAnimator();
		if (!placeholder.equals(mLastPsText) && !mLastPsText.isEmpty()) {
			setTextSmooth(mRdsPs, placeholder);
		} else {
			mRdsPs.setText(placeholder);
		}
		mLastPsText = placeholder;

		if (!placeholder.equals(mLastPiText) && !mLastPiText.isEmpty()) {
			setTextSmooth(mRdsPi, placeholder);
		} else {
			mRdsPi.setText(placeholder);
		}
		mLastPiText = placeholder;

		mLastRtText = "";
		final int mutedColor = MaterialColors.getColor(
				mRdsRt,
				com.google.android.material.R.attr.colorOnSurfaceVariant,
				ContextCompat.getColor(getContext(), R.color.text_secondary)
		);
		mRdsRt.setTextColor(mutedColor);
		if (!mShowingEmptyRt) {
			mShowingEmptyRt = true;
			setTextSmooth(mRdsRt, getContext().getString(R.string.rds_rt_empty));
		} else {
			mRdsRt.setText(R.string.rds_rt_empty);
		}
		showRtPlusFields(RtPlusInfo.EMPTY);
		if (mBtnToggleRtPlus != null) {
			mBtnToggleRtPlus.setVisibility(View.GONE);
		}
		setRtPlusMode(false);
		mLastPtyText = placeholder;
		if (!mStatusShown) {
			updatePtyDisplay(getContext().getString(R.string.rds_pty_prefix), placeholder);
		}
	}
}
