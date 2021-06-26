package com.vlad805.fmradio.activity;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.fragments.PreferencesFragment;

public class SettingsActivity extends AppCompatActivity {

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
}
