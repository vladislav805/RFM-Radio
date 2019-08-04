package com.vlad805.fmradio;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;
import com.vlad805.fmradio.view.FrequencyView;

import java.util.Locale;

public class MainActivity extends Activity implements View.OnClickListener, FrequencyView.OnFrequencyChanged {

	private boolean mServiceConnected = false;
	private FMService mService;

	private FrequencyView mFrequencyInfo;

	private RadioReceiver mRadioReceiver;

	private ImageButton mCtlToggle;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mFrequencyInfo = findViewById(R.id.frequency_info);
		mFrequencyInfo.setOnFrequencyChangedListener(this);
		mFrequencyInfo.setFrequency(102400);

		mRadioReceiver = new RadioReceiver();



		initButtons();

	}

	@Override
	protected void onStart() {
		super.onStart();

		Intent intent = new Intent(this, FMService.class);
		bindService(intent, connection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		unbindService(connection);
		mServiceConnected = false;
	}

	@Override
	protected void onResume() {
		super.onResume();

		IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.FREQUENCY_SET);
		filter.addAction(C.Event.UPDATE_PS);
		//filter.addAction(C.Event.UPDATE_RSSI);
		filter.addAction(C.Event.SEARCH_DONE);
		registerReceiver(mRadioReceiver, filter);

		__updateToggleButton();
	}

	@Override
	protected void onPause() {
		unregisterReceiver(mRadioReceiver);

		super.onPause();
	}

	private void initButtons() {

		mCtlToggle = findViewById(R.id.ctl_toggle);

		int[] ids = {
				R.id.ctl_toggle,
				R.id.ctl_go_down,
				R.id.ctl_go_up,
				R.id.ctl_seek_down,
				R.id.ctl_seek_up
		};

		for (int id : ids) {
			findViewById(id).setOnClickListener(this);
		}
	}

	private void fetchInfo() {
		mFrequencyInfo.setFrequency(mService.getStation().getFrequency());
	}

	@Override
	public void onClick(View view) {
		switch (view.getId()) {
			case R.id.ctl_toggle:
				if (FM.getState() == FM.State.TURN_OFF) {
					FM.send(this, C.FM_ENABLE);
				} else {
					FM.send(this, C.FM_DISABLE);
				}
				__updateToggleButton();
				break;

			/*case R.id.ctl_go_down:
				FM.send(this, C.FM_SET_FREQUENCY);
				break;*/

			case R.id.ctl_seek_down:
				FM.send(this, C.FM_HW_SEEK, C.FM_KEY_SEEK_HW_DIRECTION, "0");
				break;

			case R.id.ctl_seek_up:
				FM.send(this, C.FM_HW_SEEK, C.FM_KEY_SEEK_HW_DIRECTION, "1");
				break;


		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);;
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		switch (item.getItemId()) {
			case R.id.menu_stop:
				FM.send(this, C.FM_DISABLE);
				break;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onChanged(int kHz) {
		Toast.makeText(this, String.format(Locale.ENGLISH, "%5.1f", kHz / 1000d), Toast.LENGTH_LONG).show();
		FM.send(this, C.FM_SET_FREQUENCY, C.KEY_FREQUENCY, String.valueOf(kHz));
	}

	private void __updateToggleButton() {
		mCtlToggle.setImageResource(FM.getState() == FM.State.TURN_ON ? R.drawable.ic_pause : R.drawable.ic_play);
	}

	public class RadioReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			switch (intent.getAction()) {
				/*case C.Event.UPDATE_RSSI:
					setText(R.id.text_rssi, String.valueOf(intent.getIntExtra(C.KEY_RSSI, -2)));
					break;*/

				case C.Event.FREQUENCY_SET:
					final int kHz = intent.getIntExtra(C.KEY_FREQUENCY, -1);
					runOnUiThread(() -> mFrequencyInfo.setFrequency(kHz));
					break;

				case C.Event.UPDATE_PS:
					String ps = intent.getStringExtra(C.KEY_PS);

					runOnUiThread(() -> mFrequencyInfo.setRdsPs(ps));
					break;

			}
		}
	}

	private ServiceConnection connection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			FMService.LocalBinder binder = (FMService.LocalBinder) service;
			mService = binder.getService();
			mServiceConnected = true;

			fetchInfo();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mServiceConnected = false;
		}
	};

}
