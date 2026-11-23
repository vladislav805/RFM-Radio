package com.vlad805.fmradio.activity;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.EventListener;
import com.vlad805.fmradio.controller.RadioClient;
import com.vlad805.fmradio.fragments.PreferencesFragment;

public class SettingsActivity extends AppCompatActivity {
	private RadioClient mRadioClient;

	private final EventListener mEventListener = new EventListener() {
		@Override
		public void onInvalidAntennaError() {
			Toast.makeText(SettingsActivity.this, R.string.pref_tuner_antenna_error, Toast.LENGTH_SHORT).show();
		}
	};

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		DynamicColors.applyToActivityIfAvailable(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		setSupportActionBar(findViewById(R.id.settings_toolbar));
		if (savedInstanceState == null) {
			getSupportFragmentManager()
					.beginTransaction()
					.replace(R.id.settings, new PreferencesFragment())
					.commit();
		}

		final ActionBar ab = getSupportActionBar();

		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		mRadioClient = new RadioClient(this, null, mEventListener);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (mRadioClient != null) {
			mRadioClient.connect();
		}
	}

	@Override
	protected void onStop() {
		if (mRadioClient != null) {
			mRadioClient.disconnect();
		}
		super.onStop();
	}
}
