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
import com.vlad805.fmradio.*;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.preferences.Vars;
import net.grandcentrix.tray.AppPreferences;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static com.vlad805.fmradio.Utils.*;

/**
 * vlad805 (c) 2020
 */
public class PreferencesFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceClickListener {
	private ProgressDialog mProgress;
	private AppPreferences mPreferences;

	@Override
	public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
		final Context context = Objects.requireNonNull(getContext());
		mPreferences = new AppPreferences(context);

		final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

		makeTunerCategoryPreferences(context, screen);
		makeRdsCategoryPreferences(context, screen);
		makeAudioCategoryPreferences(context, screen);
		makeRecordingCategoryPreferences(context, screen);
		makeApplicationCategoryPreferences(context, screen);
		makeNotificationsCategoryPreferences(context, screen);
		makeInfoCategoryPreferences(context, screen);

		setPreferenceScreen(screen);


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
		region.setDefaultValue(C.PrefDefaultValue.TUNER_REGION);
		setListProviderAndEntries(region, Vars.sTunerRegions);
		region.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_REGION, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(region);


		final ListPreference spacing = new ListPreference(context);
		populatePreference(spacing, C.PrefKey.TUNER_SPACING, R.string.pref_tuner_spacing, R.drawable.ic_step_freq);
		spacing.setDefaultValue(C.PrefDefaultValue.TUNER_SPACING);
		setListProviderAndEntries(spacing, Vars.sTunerSpacing);
		spacing.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_SPACING, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(spacing);


		final SwitchPreference stereo = new SwitchPreference(context);
		populatePreference(stereo, C.PrefKey.TUNER_STEREO, R.string.pref_tuner_stereo, 0);
		stereo.setDefaultValue(C.PrefDefaultValue.TUNER_STEREO);
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
		setEditTextMessageAndProvider(antenna, InputType.TYPE_CLASS_NUMBER);
		antenna.setDialogMessage(R.string.pref_tuner_antenna_message);
		antenna.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_ANTENNA, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(antenna);


		final SwitchPreference power = new SwitchPreference(context);
		populatePreference(power, C.PrefKey.TUNER_POWER_MODE, R.string.pref_tuner_power_mode, R.drawable.ic_power);
		power.setDefaultValue(C.PrefDefaultValue.TUNER_POWER_MODE);
		power.setSummaryOn(R.string.pref_tuner_power_mode_low);
		power.setSummaryOff(R.string.pref_tuner_power_mode_normal);
		power.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.TUNER_POWER_MODE, (boolean) newValue);
			return true;
		});
		category.addPreference(power);
	}

	private void makeRdsCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_rds, "rds");
		screen.addPreference(category);

		final Preference description = new Preference(context);
		description.setKey("tuner_rds_description");
		description.setSummary(R.string.pref_tuner_rds_description);
		description.setEnabled(false);
		category.addPreference(description);

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

	private void makeAudioCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_audio, "audio");
		screen.addPreference(category);

		final ListPreference service = new ListPreference(context);
		populatePreference(service, C.PrefKey.AUDIO_SERVICE, R.string.pref_audio_service, R.drawable.ic_audio_service);
		service.setDefaultValue(C.PrefDefaultValue.AUDIO_SERVICE);
		setListProviderAndEntries(service, Vars.sAudioService);
		service.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.AUDIO_SERVICE, Utils.parseInt((String) newValue));
			warnApplyPreferenceOnlyAfterRestartApp();
			return true;
		});
		category.addPreference(service);


		final ListPreference source = new ListPreference(context);
		populatePreference(source, C.PrefKey.AUDIO_SOURCE, R.string.pref_audio_source, R.drawable.ic_audio_source);
		source.setDefaultValue(C.PrefDefaultValue.AUDIO_SOURCE);
		setListProviderAndEntries(source, Vars.sAudioSource);
		source.setOnPreferenceChangeListener((preference, newValue) -> {
			mPreferences.put(C.PrefKey.AUDIO_SOURCE, Utils.parseInt((String) newValue));
			warnApplyPreferenceOnlyAfterRestartApp();
			return true;
		});
		category.addPreference(source);
	}

	private void makeRecordingCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_audio, "recording");
		screen.addPreference(category);

		final EditTextPreference directory = new EditTextPreference(context);
		populatePreference(directory, C.PrefKey.RECORDING_DIRECTORY, R.string.pref_recording_path_title, R.drawable.ic_directory);
		directory.setDefaultValue(R.string.pref_recording_path_value);
		directory.setDialogMessage(R.string.pref_recording_path_name_description);
		setEditTextMessageAndProvider(directory, InputType.TYPE_CLASS_TEXT);
		directory.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.RECORDING_DIRECTORY, (String) newValue);
			return true;
		});
		category.addPreference(directory);

		final EditTextPreference filename = new EditTextPreference(context);
		populatePreference(filename, C.PrefKey.RECORDING_FILENAME, R.string.pref_recording_name_title, R.drawable.ic_file_music);
		filename.setDefaultValue(R.string.pref_recording_name_value);
		filename.setDialogMessage(R.string.pref_recording_path_name_description);
		setEditTextMessageAndProvider(filename, InputType.TYPE_CLASS_TEXT);
		filename.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.RECORDING_FILENAME, (String) newValue);
			return true;
		});
		category.addPreference(filename);

		final ListPreference format = new ListPreference(context);
		populatePreference(format, C.PrefKey.RECORDING_FORMAT, R.string.pref_recording_format_title, R.drawable.ic_recording_format);
		format.setDefaultValue(0);
		setListProviderAndEntries(format, Vars.sRecordFormat);
		format.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.RECORDING_FORMAT, Utils.parseInt((String) newValue));
			return true;
		});
		category.addPreference(format);

		final SwitchPreferenceCompat notify = new SwitchPreferenceCompat(context);
		populatePreference(notify, C.PrefKey.RECORDING_SHOW_NOTIFY, R.string.pref_recording_show_notify, R.drawable.ic_info);
		notify.setDefaultValue(true);
		notify.setSummary(R.string.pref_recording_show_notify_summary);
		notify.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.RECORDING_SHOW_NOTIFY, (boolean) newValue);
			return true;
		});
		category.addPreference(notify);
	}

	private void makeApplicationCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_app, "app");
		screen.addPreference(category);

		final SwitchPreferenceCompat autostart = new SwitchPreferenceCompat(context);
		populatePreference(autostart, C.PrefKey.APP_AUTO_STARTUP, R.string.pref_app_auto_startup, R.drawable.ic_autostart);
		autostart.setDefaultValue(C.PrefDefaultValue.APP_AUTO_STARTUP);
		autostart.setSummaryOn(R.string.pref_app_auto_startup_enabled);
		autostart.setSummaryOff(R.string.pref_auto_startup_disabled);
		autostart.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.NOTIFICATION_SHOW_RDS, (boolean) newValue);
			return true;
		});
		category.addPreference(autostart);
	}

	private void makeNotificationsCategoryPreferences(final Context context, final PreferenceScreen screen) {
		final PreferenceCategory category = makeCategory(context, R.string.pref_header_notifications, "notifications");
		screen.addPreference(category);

		final SwitchPreferenceCompat showPs = new SwitchPreferenceCompat(context);
		populatePreference(showPs, C.PrefKey.NOTIFICATION_SHOW_RDS, R.string.pref_notification_show_ps, R.drawable.ic_ps_name);
		showPs.setDefaultValue(C.PrefDefaultValue.NOTIFICATION_SHOW_RDS);
		showPs.setSummaryOn(R.string.pref_notification_show_ps_enabled);
		showPs.setSummaryOff(R.string.pref_notification_show_ps_disabled);
		showPs.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.NOTIFICATION_SHOW_RDS, (boolean) newValue);
			return true;
		});
		category.addPreference(showPs);


		final SwitchPreferenceCompat seekByFav = new SwitchPreferenceCompat(context);
		populatePreference(seekByFav, C.PrefKey.NOTIFICATION_SEEK_BY_FAVORITES, R.string.pref_notification_seek_by_favorites, R.drawable.ic_seek_arrows);
		seekByFav.setDefaultValue(C.PrefDefaultValue.NOTIFICATION_SEEK_BY_FAVORITES);
		seekByFav.setSummaryOn(R.string.pref_notification_seek_by_favorites_enabled);
		seekByFav.setSummaryOff(R.string.pref_notification_seek_by_favorites_disabled);
		seekByFav.setOnPreferenceChangeListener((preference, newValue) -> {
			Storage.getInstance(context).put(C.PrefKey.NOTIFICATION_SEEK_BY_FAVORITES, (boolean) newValue);
			return true;
		});
		category.addPreference(seekByFav);
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

	private void setEditTextMessageAndProvider(final EditTextPreference preference, final int type) {
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
		final Context context = Objects.requireNonNull(getContext());

		switch (preference.getKey()) {
			case "pref_info_version": {
				checkVersion();
				break;
			}

			case "info_links_site": {
				final String url = "https://rfm.velu.ga/?ref=app&hl=" + Utils.getCountryISO(context);
				browseUrl(context, url);
				return true;
			}

			case "info_links_telegram": {
				browseUrl(context, "https://t.me/RFMRadioApp");
				return true;
			}

			case "info_links_github": {
				Utils.browseUrl(context, "https://github.com/vladislav805/RFM-Radio");
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
		fetch("https://rfm.velu.ga/app_version.json", new Utils.FetchCallback() {
			@Override
			public void onSuccess(final JSONObject json) {
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
					title = PreferencesFragment.this.getString(R.string.pref_version_check_fresh_title);
					content = PreferencesFragment.this.getString(R.string.pref_version_check_fresh_content);
				} else {
					title = PreferencesFragment.this.getString(R.string.pref_version_check_need_update_title);
					content = PreferencesFragment.this.getString(
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
						PreferencesFragment.this.startActivity(intent);
					});
				}

				ab.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());

				ab.create().show();
			}

			@Override
			public void onError(final Throwable exception) {
				mProgress.hide();

				Toast.makeText(getContext(), getString(R.string.pref_version_check_error, exception.getMessage()), Toast.LENGTH_LONG).show();
			}
		});
	}
}
