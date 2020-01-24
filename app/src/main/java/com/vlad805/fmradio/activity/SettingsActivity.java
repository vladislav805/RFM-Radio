package com.vlad805.fmradio.activity;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import androidx.annotation.NonNull;
import com.vlad805.fmradio.R;

@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements PreferenceFragment.OnPreferenceStartFragmentCallback {

	private static final String TITLE_TAG = "title";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences_main);
	}

	/**
	 * Save current activity title so we can set it again after a configuration change
	 * @param outState State
	 */
	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);

		outState.putCharSequence(TITLE_TAG, getTitle());
	}

	@Override
	public boolean onNavigateUp() {
		if (getFragmentManager().popBackStackImmediate()) {
			return true;
		}
		return super.onNavigateUp();
	}

	@Override
	public boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref) {
		setTitle(pref.getTitle());
		return true;
	}


}
