package com.vlad805.fmradio.activity;

import androidx.core.content.ContextCompat;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.C;
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

	@Override
	protected void onResume() {
		super.onResume();

		final IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.ERROR_INVALID_ANTENNA);

		// --- FIX ---
		ContextCompat.registerReceiver(this, mEventListener, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
		// -----------
	}

	@Override
	protected void onPause() {
		super.onPause();
		// --- FIX ---
		// Add try-catch for robustness
		try {
			unregisterReceiver(mEventListener);
		} catch (IllegalArgumentException e) {
			// Ignore if already unregistered
		}
		// -----------
	}

	private final BroadcastReceiver mEventListener = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			final String action = intent.getAction();

			if (action == null) {
				return;
			}

            if (action.equals(C.Event.ERROR_INVALID_ANTENNA)) {
                Toast.makeText(SettingsActivity.this, R.string.pref_tuner_antenna_error, Toast.LENGTH_SHORT).show();
            }
		}
	};
}
