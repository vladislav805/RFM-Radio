package com.vlad805.fmradio.activity;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.fragments.PreferencesFragment;

@SuppressWarnings("deprecation")
public class SettingsActivity extends AppCompatActivity {

	private static final String TITLE_TAG = "title";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.settings, new PreferencesFragment())
				.commit();
	}
}
