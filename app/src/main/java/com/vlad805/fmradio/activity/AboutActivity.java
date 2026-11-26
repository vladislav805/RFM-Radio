package com.vlad805.fmradio.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.TunerDriverDetector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class AboutActivity extends AppCompatActivity {
	private static final String URL_GITHUB = "https://github.com/vladislav805/RFM-Radio";
	private static final String URL_RELEASES = URL_GITHUB + "/releases";
	private static final String URL_TELEGRAM = "https://t.me/RFMRadioApp";

	private static final class LicenseEntry {
		final String title;
		final String licenseName;
		final String assetPath;

		LicenseEntry(final String title, final String licenseName, final String assetPath) {
			this.title = title;
			this.licenseName = licenseName;
			this.assetPath = assetPath;
		}
	}

	private static final LicenseEntry[] LICENSES = {
			new LicenseEntry("RFM Radio", "GNU GPL v3.0", "licenses/gpl-3.0.txt"),
			new LicenseEntry("AndroidX Libraries", "Apache License 2.0", "licenses/apache-2.0.txt"),
			new LicenseEntry("Material Components for Android", "Apache License 2.0", "licenses/apache-2.0.txt"),
			new LicenseEntry("LAME MP3 Encoder", "GNU LGPL v2.0", "licenses/lame-lgpl-2.0.txt"),
			new LicenseEntry("Opus / libopusenc", "BSD", "licenses/opus-bsd.txt"),
	};

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

		final String versionLabel = BuildConfig.DEBUG
				? BuildConfig.VERSION_NAME + " (DEV)"
				: BuildConfig.VERSION_NAME;
		final TextView versionView = findViewById(R.id.about_version);
		versionView.setText(getString(R.string.about_version_format, versionLabel));
		versionView.setOnClickListener(v -> {
			final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
			if (clipboard != null) {
				clipboard.setPrimaryClip(ClipData.newPlainText("version", "v" + versionLabel));
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

		findViewById(R.id.about_link_telegram).setOnClickListener(v -> Utils.browseUrl(this, URL_TELEGRAM));
		findViewById(R.id.about_link_github).setOnClickListener(v -> Utils.browseUrl(this, URL_GITHUB));
		findViewById(R.id.about_link_site).setOnClickListener(
				v -> Utils.browseUrl(this, "https://rfm.velu.ga/?ref=app&hl=" + Utils.getLanguageCode(this))
		);

		findViewById(R.id.about_licenses).setOnClickListener(v -> showLicensesDialog());

		final TextView changelogSubtitle = findViewById(R.id.about_changelog_subtitle);
		changelogSubtitle.setText(getString(R.string.about_changelog_subtitle, BuildConfig.VERSION_NAME));
		findViewById(R.id.about_changelog).setOnClickListener(v -> Utils.browseUrl(this, URL_RELEASES));

		animateEntrance(findViewById(R.id.about_hero), 0L);
		animateEntrance(findViewById(R.id.about_section_community), 50L);
		animateEntrance(findViewById(R.id.about_section_information), 100L);
	}

	private void showLicensesDialog() {
		final CharSequence[] items = new CharSequence[LICENSES.length];
		for (int i = 0; i < LICENSES.length; i++) {
			items[i] = LICENSES[i].title + " — " + LICENSES[i].licenseName;
		}

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.about_licenses_title)
				.setItems(items, (dialog, which) -> {
					if (which >= 0 && which < LICENSES.length) {
						showLicenseTextDialog(LICENSES[which]);
					}
				})
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private void showLicenseTextDialog(final LicenseEntry entry) {
		final String text = readAssetText(entry.assetPath);
		final int padH = Math.round(24f * getResources().getDisplayMetrics().density);
		final int padV = Math.round(12f * getResources().getDisplayMetrics().density);

		final TextView textView = new TextView(this);
		textView.setText(text);
		textView.setTextSize(12f);
		textView.setLineSpacing(0f, 1.25f);
		textView.setTextIsSelectable(true);
		textView.setPadding(padH, padV, padH, padV);

		final ScrollView scrollView = new ScrollView(this);
		scrollView.addView(textView);

		new MaterialAlertDialogBuilder(this)
				.setTitle(entry.title + " (" + entry.licenseName + ")")
				.setView(scrollView)
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private String readAssetText(final String assetPath) {
		try (
				InputStream in = getAssets().open(assetPath);
				BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
		) {
			final StringBuilder result = new StringBuilder();
			final StringBuilder paragraph = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				final String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					if (paragraph.length() > 0) {
						if (result.length() > 0) {
							result.append("\n\n");
						}
						result.append(paragraph);
						paragraph.setLength(0);
					}
					continue;
				}
				if (paragraph.length() > 0) {
					paragraph.append(' ');
				}
				paragraph.append(trimmed);
			}
			if (paragraph.length() > 0) {
				if (result.length() > 0) {
					result.append("\n\n");
				}
				result.append(paragraph);
			}
			return result.toString();
		} catch (final IOException e) {
			return "";
		}
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
