package com.vlad805.fmradio.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.fragments.PreferencesFragment;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		setSupportActionBar(findViewById(R.id.settings_toolbar));
		getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.settings, new PreferencesFragment())
				.commit();

		ActionBar ab = getSupportActionBar();

		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
	}

	@Override
	protected void onDestroy() {
		PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
		super.onDestroy();
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private static final String[] sGentleSettings = {
			"tuner_driver",
			"tuner_rds",
			"tuner_antenna",
			"tuner_step",
			"audio_service",
			"audio_source",
			"app_auto_startup"
	};

	private boolean inArray(final String needle) {
		for (final String str : sGentleSettings) {
			if (str.equals(needle)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void onSharedPreferenceChanged(final SharedPreferences sp, final String key) {
		if (inArray(key)) {
			final Intent intent = new Intent().putExtra("changed", true);
			setResult(RESULT_OK, intent);
		}
	}

}
