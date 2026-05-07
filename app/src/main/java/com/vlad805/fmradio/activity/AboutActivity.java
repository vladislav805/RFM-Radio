package com.vlad805.fmradio.activity;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;

public class AboutActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_about);

		setSupportActionBar(findViewById(R.id.about_toolbar));

		final ActionBar ab = getSupportActionBar();
		if (ab != null) {
			ab.setDisplayHomeAsUpEnabled(true);
		}

		((TextView) findViewById(R.id.about_version)).setText(BuildConfig.VERSION_NAME);

		final View github = findViewById(R.id.about_link_github);
		final View telegram = findViewById(R.id.about_link_telegram);
		final View site = findViewById(R.id.about_link_site);

		github.setOnClickListener(v -> Utils.browseUrl(this, "https://github.com/vladislav805/RFM-Radio"));
		telegram.setOnClickListener(v -> Utils.browseUrl(this, "https://t.me/RFMRadioApp"));
		site.setOnClickListener(v -> Utils.browseUrl(this, "https://rfm.velu.ga/?ref=app&hl=" + Utils.getCountryISO(this)));
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
