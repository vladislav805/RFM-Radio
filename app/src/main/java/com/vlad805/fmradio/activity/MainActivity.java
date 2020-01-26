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
import androidx.annotation.IdRes;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.fm.FMState;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.FavoritesPanelView;
import com.vlad805.fmradio.view.RadioUIView;

public class MainActivity extends Activity implements View.OnClickListener, FavoritesPanelView.OnFavoriteClick {
	private ProgressDialog mProgress;
	private Toast mToast;
	private RadioUIView mFrequencyInfo;
	private FavoritesPanelView mFavoriteList;

	private RadioController mRadioController;
	private RadioEventReceiver mRadioEventReceiver;

	private ImageButton mCtlToggle;

	private TextView mViewRssi;
	private ImageView mViewRssiIcon;
	private ImageView mViewStereoMode;

	private Menu mMenu;

	private static final int REQUEST_CODE_FAVORITES_OPENED = 1048;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mProgress = ProgressDialog.create(this).text(getString(R.string.progress_init, BuildConfig.VERSION_NAME));
		mProgress.show();
		mToast = Toast.create(this);

		initUserInterface();
		initLogic();
	}

	/**
	 * Activity handle, UI setup
	 */
	private void initUserInterface() {
		mFrequencyInfo = findViewById(R.id.frequency_info);

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
		mRadioController = RadioController.getInstance(this);
		mRadioEventReceiver = new RadioEventReceiver();

		mRadioController.setup();

		mFrequencyInfo.setRadioController(mRadioController);
	}

	private final String[] SUPPORTED_EVENTS = {
			C.Event.FM_READY,
			C.Event.FREQUENCY_SET,
			C.Event.UPDATE_PS,
			C.Event.UPDATE_RT,
			C.Event.UPDATE_RSSI,
			C.Event.UPDATE_STEREO,
			C.Event.SEARCH_DONE,
			C.Event.RECORD_STARTED,
			C.Event.RECORD_TIME_UPDATE,
			C.Event.RECORD_ENDED,
			C.Event.KILL
	};

	/**
	 * Lifecycle: application is now active
	 * When application will be showed, we need update info on activity
	 */
	@Override
	protected void onResume() {
		super.onResume();

		IntentFilter filter = new IntentFilter();

		for (String event : SUPPORTED_EVENTS) {
			filter.addAction(event);
		}

		registerReceiver(mRadioEventReceiver, filter);
	}

	/**
	 * Lifecycle: application is now not active
	 * When application hide or close, we not need update info - unsubscribe
	 */
	@Override
	protected void onPause() {
		unregisterReceiver(mRadioEventReceiver);

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
		mViewRssiIcon = findViewById(R.id.rssi_icon);
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
				int state = mRadioController.getState().getState();
				if (state == FMState.STATE_ON) {
					mRadioController.disable();
				} else {
					mRadioController.enable();
				}
				break;

			case R.id.ctl_go_down:
				mRadioController.jump(Direction.DOWN);
				break;

			case R.id.ctl_go_up:
				mRadioController.jump(Direction.UP);
				break;

			case R.id.ctl_seek_down:
				mRadioController.hwSeek(Direction.DOWN);
				break;

			case R.id.ctl_seek_up:
				mRadioController.hwSeek(Direction.UP);
				break;

			case R.id.favorite_button:
				startActivityForResult(new Intent(this, FavoritesListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);
				break;
		}
	}

	@Override
	public void onFavoriteClick(FavoriteStation station) {
		mRadioController.setFrequency(station.getFrequency());
	}

	@Override
	public int getCurrentFrequencyForAddFavorite() {
		return mRadioController.getState().getFrequency();
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
				mRadioController.kill();
				break;

			case R.id.menu_about:
				startActivity(new Intent(this, AboutActivity.class));
				break;
		}

		return super.onOptionsItemSelected(item);
	}

	private void handleEvent(final Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return;
		}

		boolean enabled = mRadioController.getState().getState() == FMState.STATE_ON;
		mCtlToggle.setImageResource(enabled ? R.drawable.ic_stop : R.drawable.ic_play);

		switch (intent.getAction()) {
			case C.Event.FM_READY:
				mProgress.close();
				/*if (intent.hasExtra(C.Key.STATION_LIST)) {
					List<IStation> s = convert(intent.getParcelableArrayExtra(C.Key.STATION_LIST));
					Log.i("MA", "StationList = " + s.size());

					mFrequencyInfo.notifyStationsLists(s);
				}*/
				break;

			case C.Event.FREQUENCY_SET:
				final int kHz = intent.getIntExtra(C.Key.FREQUENCY, -1);
				mFrequencyInfo.setFrequency(kHz);
				String str = getString(R.string.player_event_frequency_changed, kHz / 1000f);
				mToast.text(str).show();

				mFrequencyInfo.setRdsPs("");
				mFrequencyInfo.setRdsRt("");
				mViewRssi.setText("……");
				break;

			case C.Event.UPDATE_PS:
				String ps = intent.getStringExtra(C.Key.PS);
				mFrequencyInfo.setRdsPs(ps);
				break;

			case C.Event.UPDATE_RT:
				String rt = intent.getStringExtra(C.Key.RT);
				mFrequencyInfo.setRdsRt(rt);
				break;

			case C.Event.UPDATE_RSSI: {
				int rssi = intent.getIntExtra(C.Key.RSSI, -1);
				mViewRssi.setText(String.format("%02d", rssi));
				setRssiIcon(rssi);
				break;
			}

			case C.Event.UPDATE_STEREO:
				boolean isStereo = intent.getBooleanExtra(C.Key.STEREO_MODE, false);
				mViewStereoMode.setImageResource(isStereo ? R.drawable.ic_stereo : R.drawable.ic_mono);
				break;

		}
	}

	private static final @IdRes	int[] SIGNAL_RES_ID = {
			R.drawable.ic_signal_4,
			R.drawable.ic_signal_3,
			R.drawable.ic_signal_2,
			R.drawable.ic_signal_1,
			R.drawable.ic_signal_0
	};

	private static final int[] SIGNAL_THRESHOLD = {
			60,
			50,
			40,
			30,
			20
	};

	private void setRssiIcon(final int rssi) {
		for (int i = 0; i < SIGNAL_RES_ID.length; ++i) {
			if (rssi > SIGNAL_THRESHOLD[i]) {
				mViewRssiIcon.setImageResource(SIGNAL_RES_ID[i]);
				break;
			}
		}
	}

	/**
	 * Event listener
	 */
	public class RadioEventReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || intent.getAction() == null) {
				return;
			}

			mRadioController.onEvent(intent);
			runOnUiThread(() -> handleEvent(intent));
		}
	}
}
