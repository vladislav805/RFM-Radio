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
import java.util.Map;

/**
 * vlad805 (c) 2020
 */
public class PreferencesFragment extends PreferenceFragmentCompat {

	private static Map<String, String> mTunerDrivers;

	static {
		mTunerDrivers = new HashMap<>();
		mTunerDrivers.put("0", "QualComm new");
		mTunerDrivers.put("1", "Spirit3 (needed installed Spirit3)");
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		setPreferencesFromResource(R.xml.preferences_main, rootKey);


		setNumberMessageAndProvider("tuner_antenna", R.string.pref_tuner_antenna_message);
		setNumberMessageAndProvider("audio_source", R.string.pref_tuner_audio_source_message);

		ListPreference lp = findPreference("tuner_driver");
		assert lp != null;
		lp.setSummaryProvider((Preference.SummaryProvider<ListPreference>) s -> mTunerDrivers.get(s.getValue()));
		lp.setEntries(mTunerDrivers.values().toArray(new String[0]));
		lp.setEntryValues(mTunerDrivers.keySet().toArray(new String[0]));
	}

	private void setNumberMessageAndProvider(String key, @StringRes int resId) {
		EditTextPreference preference = findPreference(key);

		if (preference != null) {
			preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) EditTextPreference::getText);
			preference.setDialogMessage(getString(resId));
			preference.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER));
		}
	}

}
