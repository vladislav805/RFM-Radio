package com.vlad805.fmradio.fragments;

import android.os.Bundle;
import android.text.InputType;
import androidx.annotation.StringRes;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.vlad805.fmradio.R;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * vlad805 (c) 2020
 */
public class PreferencesFragment extends PreferenceFragmentCompat {

	private static Map<String, String> mTunerDrivers;
	private static Map<String, String> mAudioService;
	private static Map<String, String> mAudioSource;

	static {
		mTunerDrivers = new HashMap<>();
		mTunerDrivers.put("0", "QualComm new");
		mTunerDrivers.put("1", "Spirit3 (needed installed Spirit3)");

		mAudioService = new HashMap<>();
		mAudioService.put("0", "Light audio service");
		mAudioService.put("1", "Audio service from Spirit3");

		mAudioSource = new LinkedHashMap<>();
		mAudioSource.put("0", "0, DEFAULT");
	 	mAudioSource.put("1", "1, MIC");
	 	mAudioSource.put("2", "2, VOICE_UPLINK");
		mAudioSource.put("3", "3, VOICE_DOWNLINK");
	 	mAudioSource.put("4", "6, VOICE_CALL");
		mAudioSource.put("5", "5, CAMCORDER");
		mAudioSource.put("6", "6, VOICE_RECOGNITION");
	 	mAudioSource.put("7", "7, VOICE_COMMUNICATION");
		mAudioSource.put("8", "8, REMOTE_SUBMIX");
	 	mAudioSource.put("1998", "1998, FM");
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preferences_main, rootKey);

		setNumberMessageAndProvider("tuner_antenna", R.string.pref_tuner_antenna_message);

		setListProviderAndEntries("tuner_driver", mTunerDrivers);
		setListProviderAndEntries("audio_service", mAudioService);
		setListProviderAndEntries("audio_source", mAudioSource);
	}

	private void setNumberMessageAndProvider(final String key, final @StringRes int resId) {
		EditTextPreference preference = findPreference(key);

		if (preference != null) {
			preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
			preference.setDialogMessage(getString(resId));
			preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
		}
	}

	private void setListProviderAndEntries(final String key, final Map<String, String> entries) {
		ListPreference lp = findPreference(key);
		if (lp != null) {
			lp.setSummaryProvider((Preference.SummaryProvider<ListPreference>) s -> entries.get(s.getValue()));
			lp.setEntries(entries.values().toArray(new String[0]));
			lp.setEntryValues(entries.keySet().toArray(new String[0]));
		}
	}

}
