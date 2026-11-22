package com.vlad805.fmradio.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.DynamicColors;
import com.vlad805.fmradio.R;

public class StartupBlockedActivity extends AppCompatActivity {
    private static final String EXTRA_REASON = "reason";
    private static final int REASON_UNSUPPORTED_DEVICE = 0;
    private static final int REASON_ROOT_REQUIRED = 1;

    private int mReason;

    public static Intent forUnsupportedDevice(final Context context) {
        return createIntent(context, REASON_UNSUPPORTED_DEVICE);
    }

    public static Intent forMissingRoot(final Context context) {
        return createIntent(context, REASON_ROOT_REQUIRED);
    }

    private static Intent createIntent(final Context context, final int reason) {
        return new Intent(context, StartupBlockedActivity.class).putExtra(EXTRA_REASON, reason);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        mReason = getIntent().getIntExtra(EXTRA_REASON, REASON_UNSUPPORTED_DEVICE);

        final int titleRes = mReason == REASON_ROOT_REQUIRED
                ? R.string.root_required_title
                : R.string.unsupported_device_title;

        final int messageRes = mReason == REASON_ROOT_REQUIRED
                ? R.string.root_required_message
                : R.string.unsupported_device_message;

        setTitle(titleRes);
        setContentView(R.layout.activity_startup_blocked);
        setSupportActionBar(findViewById(R.id.startup_blocked_toolbar));
        ((TextView) findViewById(R.id.startup_blocked_title)).setText(titleRes);
        ((TextView) findViewById(R.id.startup_blocked_message)).setText(messageRes);

        final View card = findViewById(R.id.startup_blocked_card);
        final float offset = 16f * getResources().getDisplayMetrics().density;
        card.setAlpha(0f);
        card.setTranslationY(offset);
        card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240L)
                .setInterpolator(new FastOutSlowInInterpolator())
                .start();
    }
}
