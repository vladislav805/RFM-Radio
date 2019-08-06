package com.vlad805.fmradio.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.db.FavoriteStation;
import com.vlad805.fmradio.db.IStation;
import com.vlad805.fmradio.enums.JumpDirection;
import com.vlad805.fmradio.enums.SeekDirection;
import com.vlad805.fmradio.service.FM;
import com.vlad805.fmradio.view.RadioUIView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements View.OnClickListener, RadioUIView.OnFrequencyChanged {

	private View mViewLoading;
	private View mViewPlayer;
	private RadioUIView mFrequencyInfo;

	private RadioReceiver mRadioReceiver;

	private ImageButton mCtlToggle;

	private TextView mViewRssi;
	private ImageView mViewStereoMode;

	private Menu mMenu;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		if (getActionBar() != null) {
			getActionBar().hide();
		}

		mViewLoading = findViewById(R.id.main_loading);
		mViewPlayer = findViewById(R.id.main_player);

		mFrequencyInfo = findViewById(R.id.frequency_info);
		mFrequencyInfo.setOnFrequencyChangedListener(this);

		mRadioReceiver = new RadioReceiver();

		initButtons();

		FM.send(this, C.Command.INIT);
	}

	@Override
	protected void onResume() {
		super.onResume();

		IntentFilter filter = new IntentFilter();
		filter.addAction(C.Event.READY);
		filter.addAction(C.Event.FREQUENCY_SET);
		filter.addAction(C.Event.UPDATE_PS);
		filter.addAction(C.Event.UPDATE_RT);
		filter.addAction(C.Event.UPDATE_RSSI);
		filter.addAction(C.Event.SEARCH_DONE);
		filter.addAction(C.Event.UPDATE_STEREO);
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
		mViewRssi = findViewById(R.id.rssi_value);
		mViewStereoMode = findViewById(R.id.stereo_mono);

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

	@Override
	public void onClick(View view) {
		switch (view.getId()) {
			case R.id.ctl_toggle:
				if (FM.getState() == FM.State.TURN_OFF) {
					FM.send(this, C.Command.ENABLE);
				} else {
					FM.send(this, C.Command.DISABLE);
				}
				__updateToggleButton();
				break;

			case R.id.ctl_go_down:
				FM.send(this, C.Command.JUMP, C.Key.JUMP_DIRECTION, JumpDirection.DOWN.getValue());
				break;

			case R.id.ctl_go_up:
				FM.send(this, C.Command.JUMP, C.Key.JUMP_DIRECTION, JumpDirection.UP.getValue());
				break;

			case R.id.ctl_seek_down:
				FM.send(this, C.Command.HW_SEEK, C.Key.SEEK_HW_DIRECTION, SeekDirection.DOWN.getValue());
				break;

			case R.id.ctl_seek_up:
				FM.send(this, C.Command.HW_SEEK, C.Key.SEEK_HW_DIRECTION, SeekDirection.UP.getValue());
				break;

		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		mMenu = menu;
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		switch (item.getItemId()) {
			case R.id.menu_stop:
				FM.send(this, C.Command.DISABLE);
				break;

			case R.id.menu_open_debug:
				startActivity(new Intent(this, DebugActivity.class));
				break;

			case R.id.menu_list:
				startActivity(new Intent(this, StationListActivity.class));
				break;

		}

		return super.onOptionsItemSelected(item);
	}

	private Toast mToast;

	@SuppressLint("ShowToast")
	private void showToast(String str) {
		if (mToast == null) {
			mToast = Toast.makeText(this, str, Toast.LENGTH_LONG);
		}
		mToast.setText(str);
		mToast.show();
	}

	@Override
	public void onChanged(int kHz) {
		FM.send(this, C.Command.SET_FREQUENCY, C.Key.FREQUENCY, String.valueOf(kHz));
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

				case C.Event.READY:
					if (intent.hasExtra(C.PrefKey.LAST_FREQUENCY)) {
						int frequency = intent.getIntExtra(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);

						mFrequencyInfo.setFrequency(frequency);
					}

					if (intent.hasExtra(C.Key.FAVORITE_STATION_LIST)) {
						List<FavoriteStation> fs = convert(intent.getParcelableArrayExtra(C.Key.FAVORITE_STATION_LIST));
						Log.i("MA", "FavStationList = " + fs.size());
					}

					if (intent.hasExtra(C.Key.STATION_LIST)) {
						List<IStation> s = convert(intent.getParcelableArrayExtra(C.Key.STATION_LIST));
						Log.i("MA", "StationList = " + s.size());

						mFrequencyInfo.notifyStationsLists(s);
					}

					mViewLoading.setVisibility(View.GONE);
					mViewPlayer.setVisibility(View.VISIBLE);

					if (getActionBar() != null) {
						getActionBar().show();
					}
					break;

				case C.Event.FREQUENCY_SET:
					final int kHz = intent.getIntExtra(C.Key.FREQUENCY, -1);
					runOnUiThread(() -> {
						mFrequencyInfo.setFrequency(kHz);

						String str = getString(R.string.player_event_frequency_changed, kHz / 1000f);
						showToast(str);

						mFrequencyInfo.setRdsPs("");
						mFrequencyInfo.setRdsRt("");
						mViewRssi.setText("0");
					});
					break;

				case C.Event.UPDATE_PS:
					String ps = intent.getStringExtra(C.Key.PS);
					runOnUiThread(() -> mFrequencyInfo.setRdsPs(ps));
					break;

				case C.Event.UPDATE_RT:
					String rt = intent.getStringExtra(C.Key.RT);
					runOnUiThread(() -> mFrequencyInfo.setRdsRt(rt));
					break;

				case C.Event.UPDATE_RSSI:
					runOnUiThread(() -> mViewRssi.setText(String.valueOf(intent.getIntExtra(C.Key.RSSI, -1))));
					break;

				case C.Event.UPDATE_STEREO:
					runOnUiThread(() -> {
						boolean isStereo = intent.getBooleanExtra(C.Key.STEREO_MODE, false);
						mViewStereoMode.setImageResource(isStereo ? R.drawable.ic_stereo : R.drawable.ic_mono);
					});

			}
		}
	}

	private <T> List<T> convert(Parcelable[] a) {
		List<T> s = new ArrayList<>();
		for (Parcelable i : a) {
			//noinspection unchecked
			s.add((T) i);
		}
		return s;
	}
}
