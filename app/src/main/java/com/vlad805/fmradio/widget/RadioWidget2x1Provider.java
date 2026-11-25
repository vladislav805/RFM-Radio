package com.vlad805.fmradio.widget;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.vlad805.fmradio.R;

/**
 * 2x1 Material 3 homescreen player widget synced with {@link com.vlad805.fmradio.service.FMService}.
 * Expands to the full 4x1 layout when resized to 4x1 or wider.
 */
public class RadioWidget2x1Provider extends RadioWidgetProvider {
    private static final int EXPANDED_MIN_WIDTH_DP = 250;

    @Override
    protected int getLayoutResId(@Nullable final Bundle options) {
        final int minWidthDp = resolveMinWidthDp(options);
        return minWidthDp >= EXPANDED_MIN_WIDTH_DP
                ? R.layout.widget_radio_4x1
                : R.layout.widget_radio_2x1;
    }

    @Override
    protected boolean isResponsive() {
        return true;
    }
}
