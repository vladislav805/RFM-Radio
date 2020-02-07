package com.vlad805.fmradio.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.SparseArray;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.service.audio.FMAudioService;
import com.vlad805.fmradio.service.fm.FMController;
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

	private static SparseArray<String> mAudioSource;
	private ProgressDialog mProgress;

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
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preferences_main, rootKey);

		final Preference ver = findPreference("pref_info_version");
		if (ver != null) {
			ver.setSummary(String.format(Locale.ENGLISH, "v%s (build %d / %s)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE));
			ver.setOnPreferenceClickListener(this);
		}

		setNumberMessageAndProvider("tuner_antenna", InputType.TYPE_CLASS_NUMBER);
		setNumberMessageAndProvider("recording_directory", InputType.TYPE_CLASS_TEXT);
		setNumberMessageAndProvider("recording_filename", InputType.TYPE_CLASS_TEXT);

		setListProviderAndEntries("tuner_driver", FMController.sDrivers);
		setListProviderAndEntries("audio_service", FMAudioService.sService);
		setListProviderAndEntries("audio_source", mAudioSource);
	}

	private void setNumberMessageAndProvider(final String key, final int type) {
		EditTextPreference preference = findPreference(key);

		if (preference != null) {
			preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
			preference.setOnBindEditTextListener(editText -> editText.setInputType(type));
		}
	}

	private void setListProviderAndEntries(final String key, final SparseArray<String> entries) {
		ListPreference lp = findPreference(key);
		if (lp != null) {
			final List<String> keys = new ArrayList<>();
			final List<String> values = new ArrayList<>();

			for (int i = 0; i < entries.size(); ++i) {
				final int k = entries.keyAt(i);

				keys.add(String.valueOf(k));
				values.add(entries.get(k));
			}

			lp.setSummaryProvider((Preference.SummaryProvider<ListPreference>) s -> {
				final int id = entries.indexOfKey(Utils.parseInt(s.getValue()));
				return entries.valueAt(id);
			});
			lp.setEntries(values.toArray(new String[0]));
			lp.setEntryValues(keys.toArray(new String[0]));
		}
	}

	@Override
	public boolean onPreferenceClick(final Preference preference) {
		switch (preference.getKey()) {
			case "pref_info_version": {
				checkVersion();
				break;
			}
		}
		return false;
	}

	private void checkVersion() {
		final Context ctx = getContext();
		mProgress = ProgressDialog.create(ctx).text(R.string.pref_version_check_progress).show();
		new Thread(() -> fetch("http://rfm.velu.ga/app_version.json", json -> {
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
