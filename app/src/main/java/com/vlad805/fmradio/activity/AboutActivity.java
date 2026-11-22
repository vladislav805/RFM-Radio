package com.vlad805.fmradio.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.DynamicColors;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.TunerDriverDetector;

public class AboutActivity extends AppCompatActivity {
	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		DynamicColors.applyToActivityIfAvailable(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_about);

		setSupportActionBar(findViewById(R.id.about_toolbar));

		final ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		final String versionText = "v" + BuildConfig.VERSION_NAME;
		((TextView) findViewById(R.id.about_version)).setText(versionText);
		findViewById(R.id.about_version_card).setOnClickListener(v -> {
			final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
			if (clipboard != null) {
				clipboard.setPrimaryClip(ClipData.newPlainText("version", versionText));
				Toast.makeText(this, R.string.about_version_copied, Toast.LENGTH_SHORT).show();
			}
		});

		final TunerDriver driver = TunerDriverDetector.getTunerDriver(this);
		final View driverCard = findViewById(R.id.about_driver_card);
		if (driver == TunerDriver.NONE) {
			driverCard.setVisibility(View.GONE);
		} else {
			((TextView) findViewById(R.id.about_driver)).setText(driver.name());
		}

		final View github = findViewById(R.id.about_link_github);
		final View telegram = findViewById(R.id.about_link_telegram);
		final View site = findViewById(R.id.about_link_site);

		github.setOnClickListener(v -> Utils.browseUrl(this, "https://github.com/vladislav805/RFM-Radio"));
		telegram.setOnClickListener(v -> Utils.browseUrl(this, "https://t.me/RFMRadioApp"));
		site.setOnClickListener(v -> Utils.browseUrl(this, "https://rfm.velu.ga/?ref=app&hl=" + Utils.getCountryISO(this)));

		animateEntrance(findViewById(R.id.about_hero_card), 0L);
		animateEntrance(findViewById(R.id.about_links_card), 60L);
	}

	private void animateEntrance(final View view, final long delayMs) {
		final float offset = 16f * getResources().getDisplayMetrics().density;
		view.setAlpha(0f);
		view.setTranslationY(offset);
		view.animate()
				.alpha(1f)
				.translationY(0f)
				.setStartDelay(delayMs)
				.setDuration(240L)
				.setInterpolator(new FastOutSlowInInterpolator())
				.start();
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
