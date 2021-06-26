package com.vlad805.fmradio.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.SparseArray;
import android.widget.Toast;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.*;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.preferences.Vars;
import net.grandcentrix.tray.AppPreferences;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.vlad805.fmradio.Utils.fetch;
import static com.vlad805.fmradio.Utils.uiThread;

/**
 * vlad805 (c) 2020
 */
public class PreferencesFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener {

	private static final SparseArray<String> mAudioSource;
	private static final SparseArray<String> mRecordMode;
	private ProgressDialog mProgress;
	private AppPreferences mPreferences;

	static {
		mAudioSource = new SparseArray<>();
		mAudioSource.put(0, "0, DEFAULT");
	 	mAudioSource.put(1, "1, MIC");
	 	mAudioSource.put(2, "2, VOICE_UPLINK");
		mAudioSource.put(3, "3, VOICE_DOWNLINK");
	 	mAudioSource.put(4, "6, VOICE_CALL");
		mAudioSource.put(5, "5, CAMCORDER");
		mAudioSource.put(6, "6, VOICE_RECOGNITION");
	 	mAudioSource.put(7, "7, VOICE_COMMUNICATION");
		mAudioSource.put(8, "8, REMOTE_SUBMIX");
	 	mAudioSource.put(1998, "1998, FM");

		mRecordMode = new SparseArray<>();
		mRecordMode.put(0, "WAV PCM 16bit (raw, large size)");
		mRecordMode.put(1, "MP3 192kbps (compressed, small size)");
	}

	@Override
	public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
		final Context context = Objects.requireNonNull(getContext());
		mPreferences = new AppPreferences(context);

		final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

		makeTunerCategoryPreferences(context, screen);
		makeRdsCategoryPreferences(context, screen);
		makeInfoCategoryPreferences(context, screen);

		setPreferenceScreen(screen);

		/*setNumberMessageAndProvider("tuner_antenna", InputType.TYPE_CLASS_NUMBER);
		setNumberMessageAndProvider("recording_directory", InputType.TYPE_CLASS_TEXT);
		setNumberMessageAndProvider("recording_filename", InputType.TYPE_CLASS_TEXT);

		setListProviderAndEntries("audio_service", FMAudioService.sService);
		setListProviderAndEntries("audio_source", mAudioSource);
		setListProviderAndEntries("recording_mode", mRecordMode);*/
	}

	private PreferenceCategory makeCategory(final Context context, final @StringRes int title, final String key) {
		final PreferenceCategory category = new PreferenceCategory(context);
		category.setKey(key);
		category.setTitle(title);
		return category;
	}

	private void populatePreference(final Preference pref, final String key, final @StringRes int title, final @DrawableRes int icon) {
		pref.setKey(key);
		pref.setTitle(title);

		if (pref instanceof DialogPreference) {
			((DialogPreference) pref).setDialogTitle(title);
		}

		if (icon != 0) {
			pref.setIcon(icon);
		}
	}

	private void makeTunerCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_tuner, "tuner");
		screen.addPreference(category);

		final ListPreference driver = new ListPreference(context);
		populatePreference(driver, C.PrefKey.TUNER_DRIVER, R.string.pref_tuner_driver, R.drawable.ic_tuner_driver);
		driver.setDefaultValue(C.PrefDefaultValue.TUNER_DRIVER);
		setListProviderAndEntries(driver, Vars.sTunerDrivers);
		driver.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_DRIVER, Utils.parseInt((String) newValue));
			warnApplyPreferenceOnlyAfterRestartApp();
			return true;
		});
		category.addPreference(driver);


		final ListPreference region = new ListPreference(context);
		populatePreference(region, C.PrefKey.TUNER_REGION, R.string.pref_tuner_region, R.drawable.ic_band);
		region.setDefaultValue(String.valueOf(C.PrefDefaultValue.TUNER_REGION));
		setListProviderAndEntries(region, Vars.sTunerRegions);
		region.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_REGION, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(region);


		final ListPreference spacing = new ListPreference(context);
		populatePreference(spacing, C.PrefKey.TUNER_SPACING, R.string.pref_tuner_spacing, R.drawable.ic_step_freq);
		spacing.setDefaultValue(String.valueOf(C.PrefDefaultValue.TUNER_SPACING));
		setListProviderAndEntries(spacing, Vars.sTunerSpacing);
		spacing.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_SPACING, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(spacing);


		final SwitchPreference stereo = new SwitchPreference(context);
		populatePreference(stereo, C.PrefKey.TUNER_STEREO, R.string.pref_tuner_stereo, 0);
		stereo.setDefaultValue(true);
		stereo.setSummaryOn(R.string.pref_tuner_stereo_on);
		stereo.setSummaryOff(R.string.pref_tuner_stereo_off);
		stereo.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_STEREO, (boolean) newValue);
			return true;
		});
		category.addPreference(stereo);


		final EditTextPreference antenna = new EditTextPreference(context);
		populatePreference(antenna, C.PrefKey.TUNER_ANTENNA, R.string.pref_tuner_antenna, R.drawable.ic_antenna);
		antenna.setDefaultValue(C.PrefDefaultValue.TUNER_ANTENNA);
		setNumberMessageAndProvider(antenna, InputType.TYPE_CLASS_NUMBER);
		antenna.setDialogMessage(R.string.pref_tuner_antenna_message);
		category.addPreference(antenna);
	}

	private void makeRdsCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_rds, "rds");
		screen.addPreference(category);

		final SwitchPreference enable = new SwitchPreference(context);
		populatePreference(enable, C.PrefKey.RDS_ENABLE, R.string.pref_tuner_rds_enable, R.drawable.ic_rds);
		enable.setDefaultValue(true);
		enable.setSummaryOn(R.string.pref_tuner_rds_enabled);
		enable.setSummaryOff(R.string.pref_tuner_rds_disabled);
		enable.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.RDS_ENABLE, (boolean) newValue);
			return true;
		});
		category.addPreference(enable);

	}

	private void makeInfoCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_info, "info");
		screen.addPreference(category);

		final Preference github = new Preference(context);
		populatePreference(github, "info_links_github", R.string.pref_info_link_github, R.drawable.ic_github);
		github.setSummary(R.string.pref_info_link_github_summary);
		github.setOnPreferenceClickListener(this);
		category.addPreference(github);


		final Preference telegram = new Preference(context);
		populatePreference(telegram, "info_links_telegram", R.string.pref_info_link_telegram, R.drawable.ic_telegram);
		telegram.setSummary(R.string.pref_info_link_telegram_summary);
		telegram.setOnPreferenceClickListener(this);
		category.addPreference(telegram);


		final Preference site = new Preference(context);
		populatePreference(site, "info_links_site", R.string.pref_info_link_site, R.drawable.ic_web);
		site.setSummary(R.string.pref_info_link_site_summary);
		site.setOnPreferenceClickListener(this);
		category.addPreference(site);


		final Preference version = new Preference(context);
		populatePreference(version, "pref_info_version", R.string.pref_info_version_title, 0);
		version.setSummary(String.format(Locale.ENGLISH, "v%s (build %d / %s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE));
		version.setOnPreferenceClickListener(this);
		category.addPreference(version);

	}











	private void setNumberMessageAndProvider(final EditTextPreference preference, final int type) {
		preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
		preference.setOnBindEditTextListener(editText -> editText.setInputType(type));
	}

	private void setListProviderAndEntries(final ListPreference lp, final SparseArray<String> entries) {
		final List<String> keys = new ArrayList<>();
		final List<String> values = new ArrayList<>();

		for (int i = 0; i < entries.size(); ++i) {
			final int k = entries.keyAt(i);

			keys.add(String.valueOf(k));
			values.add(entries.get(k));
		}

		lp.setSummaryProvider((Preference.SummaryProvider<ListPreference>) s -> {
			int id = entries.indexOfKey(Utils.parseInt(s.getValue()));
			if (id < 0) {
				id = 0;
			}
			return entries.valueAt(id);
		});
		lp.setEntries(values.toArray(new String[0]));
		lp.setEntryValues(keys.toArray(new String[0]));
	}

	@Override
	public boolean onPreferenceClick(final Preference preference) {
		switch (preference.getKey()) {
			case "pref_info_version": {
				checkVersion();
				break;
			}

			case "info_links_site": {
				final Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setData(Uri.parse("https://rfm.velu.ga/?ref=app&hl=" + Utils.getCountryISO(Objects.requireNonNull(getContext()))));
				startActivity(intent);
				return true;
			}

			case "info_links_telegram": {
				final Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setData(Uri.parse("https://t.me/RFMRadioApp"));
				startActivity(intent);
				return true;
			}

			case "info_links_github": {
				final Intent intent = new Intent(Intent.ACTION_VIEW);
				intent.setData(Uri.parse("https://github.com/vladislav805/RFM-Radio"));
				startActivity(intent);
				return true;
			}
		}
		return false;
	}

	private void warnApplyPreferenceOnlyAfterRestartApp() {
		Toast.makeText(getContext(), R.string.pref_warning_applying_only_after_restart, Toast.LENGTH_LONG).show();
	}

	private void checkVersion() {
		final Context ctx = getContext();
		mProgress = ProgressDialog.create(ctx).text(R.string.pref_version_check_progress).show();
		new Thread(() -> fetch("https://rfm.velu.ga/app_version.json", json -> {
			mProgress.hide();

			final JSONObject latest = json.optJSONObject("latest");

			if (latest == null) {
				Toast.makeText(ctx, "err", Toast.LENGTH_SHORT).show();
				return;
			}

			final boolean isFresh = latest.optInt("build") <= BuildConfig.VERSION_CODE;

			String title;
			String content;

			if (isFresh) {
				title = getString(R.string.pref_version_check_fresh_title);
				content = getString(R.string.pref_version_check_fresh_content);
			} else {
				title = getString(R.string.pref_version_check_need_update_title);
				content = getString(
						R.string.pref_version_check_need_update_content,
						latest.optString("version"),
						latest.optInt("build"),
						BuildConfig.VERSION_NAME,
						BuildConfig.VERSION_CODE,
						latest.optString("changelog", "< not specified >")
				);
			}

			final AlertDialog.Builder ab = new AlertDialog.Builder(Objects.requireNonNull(ctx));
			ab.setTitle(title).setMessage(content);

			if (!isFresh) {
				ab.setPositiveButton(R.string.pref_version_check_need_update_ok, (dialog, which) -> {
					final Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setData(Uri.parse(latest.optString("url")));
					startActivity(intent);
				});
			}

			ab.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

			uiThread(() -> ab.create().show());
		})).start();
	}
}
