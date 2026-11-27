package com.vlad805.fmradio.recordings;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.service.recording.RdsSnapshot;
import com.vlad805.fmradio.service.recording.RfmMetadataEvent;
import com.vlad805.fmradio.view.RadioUIView;
import com.vlad805.fmradio.view.SignalStrengthView;

/**
 * Binds recorded {@link RfmMetadataEvent} snapshots onto the shared {@code include_tuner_display}
 * and {@code include_rt_plus_overlay} views inside the recordings player drawer.
 */
public final class RecordingTunerBinder {
    private final Context mContext;
    private final RadioUIView mRadioUIView;
    private final SignalStrengthView mSignalStrength;
    private final MaterialCardView mStereoCard;
    private final TextView mStereoText;
    @Nullable
    private RfmMetadataEvent mLastBoundEvent;

    public RecordingTunerBinder(@NonNull final View root) {
        mContext = root.getContext();
        mRadioUIView = new RadioUIView(root);
        mSignalStrength = root.findViewById(R.id.signal_strength);
        mStereoCard = root.findViewById(R.id.stereo_mono_card);
        mStereoText = root.findViewById(R.id.stereo_mono);
        clear();
    }

    public void bind(@Nullable final RfmMetadataEvent event) {
        if (event == mLastBoundEvent) {
            return;
        }

        mLastBoundEvent = event;

        if (event == null) {
            clearViews();
            return;
        }

        final RdsSnapshot snapshot = event.getSnapshot();
        mRadioUIView.setSnapshot(snapshot);

        if (mSignalStrength != null) {
            if (
                    snapshot.getRmssi() != RadioState.RMSSI_UNKNOWN ||
                    snapshot.getSinr() != RadioState.SINR_UNKNOWN
            ) {
                mSignalStrength.setMetrics(snapshot.getRmssi(), snapshot.getSinr());
            } else {
                mSignalStrength.clear();
            }
        }

        updateStereoBadge(snapshot.isStereo());
    }

    public void clear() {
        mLastBoundEvent = null;
        clearViews();
    }

    public void release() {
        mRadioUIView.release();
    }

    private void clearViews() {
        mRadioUIView.clearMetadata();

        if (mSignalStrength != null) {
            mSignalStrength.clear();
        }

        updateStereoBadge(false);
    }

    private void updateStereoBadge(final boolean isStereo) {
        if (mStereoCard == null || mStereoText == null) {
            return;
        }

        mStereoText.setText(isStereo ? R.string.stereo_mode_label : R.string.mono_mode_label);

        final int bgColor = MaterialColors.getColor(
                mStereoCard,
                isStereo
                        ? com.google.android.material.R.attr.colorSecondaryContainer
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh
        );
        final int strokeColor = MaterialColors.getColor(
                mStereoCard,
                isStereo
                        ? com.google.android.material.R.attr.colorSecondary
                        : com.google.android.material.R.attr.colorOutlineVariant
        );
        final int textColor = MaterialColors.getColor(
                mStereoText,
                isStereo
                        ? com.google.android.material.R.attr.colorOnSecondaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant
        );

        mStereoCard.setCardBackgroundColor(bgColor);
        mStereoCard.setStrokeColor(strokeColor);
        mStereoText.setTextColor(textColor);
    }
}
