package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.enums.JumpDirection;
import com.vlad805.fmradio.enums.SeekDirection;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.service.FM;
import com.vlad805.fmradio.view.FavoritesListView;
import com.vlad805.fmradio.view.RadioUIView;

public class MainActivity extends Activity implements View.OnClickListener, RadioUIView.OnUserFrequencyChange, FavoritesListView.OnFavoriteClick {
	private ProgressDialog mProgress;
	private Toast mToast;
	private RadioUIView mFrequencyInfo;
	private FavoritesListView mFavoriteList;

	private RadioReceiver mRadioReceiver;

	private ImageButton mCtlToggle;

	private TextView mViewRssi;
	private ImageView mViewStereoMode;

	private Menu mMenu;

	private static final int REQUEST_CODE_FAVORITES_OPENED = 1048;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mProgress = ProgressDialog.create(this).text(R.string.progress_init).show();
		mToast = Toast.create(this);

		initUserInterface();
		initLogic();
	}

	/**
	 * Activity handle, UI setup
	 */
	private void initUserInterface() {
		mFrequencyInfo = findViewById(R.id.frequency_info);
		mFrequencyInfo.setOnFrequencyChangedListener(this);

		mFavoriteList = findViewById(R.id.favorite_list);
		mFavoriteList.setOnFavoriteClick(this);

		// On small screens, the elements overlap each other
		// By removing reflection, you can give room for elements
		DisplayMetrics dm = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(dm);

		if (dm.heightPixels < 800) {
			mFrequencyInfo.hideReflection();
		}

		initClickableButtons();
	}

	/**
	 * Initialize connection and service
	 */
	private void initLogic() {
		mRadioReceiver = new RadioReceiver();

		//FM.send(this, C.Command.INIT);
	}

	/**
	 * Lifecycle: application is now active
	 * When application will be showed, we need update info on activity
	 */
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

		Intent i = new Intent(C.Event.READY).putExtra(C.PrefKey.LAST_FREQUENCY, 88100);
		sendBroadcast(i);
	}

	/**
	 * Lifecycle: application is now not active
	 * When application hide or close, we not need update info - unsubscribe
	 */
	@Override
	protected void onPause() {
		unregisterReceiver(mRadioReceiver);

		super.onPause();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		//noinspection SwitchStatementWithTooFewBranches
		switch (requestCode) {
			case REQUEST_CODE_FAVORITES_OPENED: {
				if (resultCode == Activity.RESULT_OK && data.getBooleanExtra("changed", false)) {
					mFavoriteList.reload(true);
				}
				break;
			}
		}
	}

	/**
	 * Setup clickable buttons
	 */
	private void initClickableButtons() {
		mCtlToggle = findViewById(R.id.ctl_toggle);
		mViewRssi = findViewById(R.id.rssi_value);
		mViewStereoMode = findViewById(R.id.stereo_mono);

		int[] ids = {
				R.id.ctl_toggle,
				R.id.ctl_go_down,
				R.id.ctl_go_up,
				R.id.ctl_seek_down,
				R.id.ctl_seek_up,
				R.id.favorite_button
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

			case R.id.favorite_button:
				startActivityForResult(new Intent(this, FavoritesListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);

		}
	}

	@Override
	public void onFavoriteClick(FavoriteStation station) {
		Intent i = new Intent(C.Event.FREQUENCY_SET).putExtra(C.Key.FREQUENCY, station.getFrequency());
		sendBroadcast(i);
	}

	@Override
	public int getCurrentFrequencyForAddFavorite() {
		return __DEV_currentFrequency;
	}

	/**
	 * Menu
	 */
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

			case R.id.menu_about:
				mToast.text("Authors").show();
				break;
		}

		return super.onOptionsItemSelected(item);
	}

	// TODO: realization
	@Override
	public void onUserChangeFrequency(int kHz) {
		Intent i = new Intent(C.Event.FREQUENCY_SET).putExtra(C.Key.FREQUENCY, kHz);
		sendBroadcast(i);
	}

	private int __DEV_currentFrequency = -1;

	/**
	 * Event listener
	 */
	public class RadioReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			switch (intent.getAction()) {

				case C.Event.READY:
					mProgress.close();

					if (intent.hasExtra(C.PrefKey.LAST_FREQUENCY)) {
						int frequency = intent.getIntExtra(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
__DEV_currentFrequency = frequency;
						mFrequencyInfo.setFrequency(frequency);
					}

					/*if (intent.hasExtra(C.Key.FAVORITE_STATION_LIST)) {
						List<FavoriteStation> fs = convert(intent.getParcelableArrayExtra(C.Key.FAVORITE_STATION_LIST));
						Log.i("MA", "FavStationList = " + fs.size());
					}

					if (intent.hasExtra(C.Key.STATION_LIST)) {
						List<IStation> s = convert(intent.getParcelableArrayExtra(C.Key.STATION_LIST));
						Log.i("MA", "StationList = " + s.size());

						mFrequencyInfo.notifyStationsLists(s);
					}*/

					if (getActionBar() != null) {
						getActionBar().show();
					}
					break;

				case C.Event.FREQUENCY_SET:
					final int kHz = intent.getIntExtra(C.Key.FREQUENCY, -1);
					runOnUiThread(() -> {
						mFrequencyInfo.setFrequency(kHz);
__DEV_currentFrequency = kHz;
						String str = getString(R.string.player_event_frequency_changed, kHz / 1000f);
						mToast.text(str).show();

						mFrequencyInfo.setRdsPs("");
						mFrequencyInfo.setRdsRt("");
						mViewRssi.setText("…");
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
}
