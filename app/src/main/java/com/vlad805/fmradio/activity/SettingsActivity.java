package com.vlad805.fmradio.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.fragments.PreferencesFragment;

public class SettingsActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
	private RadioController mController;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		setSupportActionBar(findViewById(R.id.settings_toolbar));
		getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.settings, new PreferencesFragment())
				.commit();

		final ActionBar ab = getSupportActionBar();

		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		mController = new RadioController(this);

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
			"tuner_spacing",
			"tuner_region",
			"tuner_power_mode",
			"audio_service",
			"audio_source",
			"recording_mode",
			"app_auto_startup",
	};

	private static final String[] sDynamicSettings = {
			"rds_enable",
			"tuner_spacing",
			"tuner_region",
	};

	private boolean inArray(final String needle, final String[] where) {
		for (final String str : where) {
			if (str.equals(needle)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void onSharedPreferenceChanged(final SharedPreferences sp, final String key) {
		if (inArray(key, sGentleSettings)) {
			final Intent intent = new Intent().putExtra("changed", true);
			setResult(RESULT_OK, intent);
		}

		Log.i("SA_OSPC", "key = " + key);
		if (inArray(key, sDynamicSettings)) {

			mController.reloadPreferences();
		}
	}
}
