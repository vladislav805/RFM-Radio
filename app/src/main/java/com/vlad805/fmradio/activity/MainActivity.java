package com.vlad805.fmradio.activity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.view.FavoritesPanelView;
import com.vlad805.fmradio.view.RadioUIView;

import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, FavoritesPanelView.OnFavoriteClick {
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

	private ImageView mRecordIcon;
	private TextView mRecordDuration;

	private Menu mMenu;

	private static final int REQUEST_CODE_FAVORITES_OPENED = 1048;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mToast = Toast.create(this);

		setSupportActionBar(findViewById(R.id.main_toolbar));

		initUserInterface();
		initLogic();
		updateControlButtonState();

		registerReceiver(mRadioEventReceiver, RadioController.sFilter);
	}

	/**
	 * Activity handle, UI setup
	 */
	private void initUserInterface() {
		mFrequencyInfo = findViewById(R.id.frequency_info);

		mFavoriteList = findViewById(R.id.favorite_list);
		mFavoriteList.setOnFavoriteClick(this);

		mRecordIcon = findViewById(R.id.record_icon);
		mRecordDuration = findViewById(R.id.record_duration);

		mRecordIcon.setOnClickListener(this);

		// On small screens, the elements overlap each other
		// By removing reflection, you can give room for elements
		DisplayMetrics dm = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(dm);

		if (dm.heightPixels < 800) {
			mFrequencyInfo.hideReflection();
		}

		mFrequencyInfo.setFrequency(Storage.getInstance(this).getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY));

		initClickableButtons();
	}

	/**
	 * Initialize connection and service
	 */
	private void initLogic() {
		mRadioController = new RadioController(this);
		mRadioEventReceiver = new RadioEventReceiver();

		final boolean needStartup = Storage.getPrefBoolean(this, C.PrefKey.APP_AUTO_STARTUP, C.PrefDefaultValue.APP_AUTO_STARTUP);

		if (needStartup) {
			setEnabledToggleButton(false);
			mRadioController.setup();
			mRadioController.launch();
		} else {
			setEnabledUi(false);
		}

		mFrequencyInfo.setRadioController(mRadioController);
	}

	private void showProgress(String text) {
		if (mProgress != null) {
			hideProgress();
		}

		mProgress = ProgressDialog.create(this).text(text).show();
	}

	private void hideProgress() {
		if (mProgress != null) {
			mProgress.hide();
			mProgress = null;
		}
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(mRadioEventReceiver);

		super.onDestroy();
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
		super.onActivityResult(requestCode, resultCode, data);
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
				setEnabledToggleButton(false);

				final Bundle state = mRadioController.getState();
				final int stage = state.getInt(C.Key.STAGE);

				switch (stage) {
					case C.FMStage.VOID: {
						mRadioController.setup();
						mRadioController.launch();
						break;
					}

					case C.FMStage.LAUNCHED: {
						mRadioController.enable();
						break;
					}

					case C.FMStage.ENABLED: {
						mRadioController.disable();
						break;
					}
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
				showProgress(getString(R.string.progress_searching));
				break;

			case R.id.ctl_seek_up:
				mRadioController.hwSeek(Direction.UP);
				showProgress(getString(R.string.progress_searching));
				break;

			case R.id.favorite_button:
				startActivityForResult(new Intent(this, FavoritesListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);
				break;

			case R.id.record_icon:
				mRadioController.record(false);
				break;
		}
	}

	@Override
	public void onFavoriteClick(FavoriteStation station) {
		mRadioController.setFrequency(station.getFrequency());
	}

	@Override
	public int getCurrentFrequencyForAddFavorite() {
		return mRadioController.getState().getInt(C.Key.FREQUENCY);
	}

	/**
	 * Menu
	 */
	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		mMenu = menu;
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_stop:
				showProgress(getString(R.string.progress_stopping));
				mRadioController.kill();
				break;

			case R.id.menu_about:
				startActivity(new Intent(this, AboutActivity.class));
				break;

			case R.id.menu_settings:
				startActivity(new Intent(this, SettingsActivity.class));
				break;

			case R.id.menu_record:
				mRadioController.record(true);
				break;
		}

		return super.onOptionsItemSelected(item);
	}

	private void setEnabledToggleButton(final boolean enabled) {
		findViewById(R.id.ctl_toggle).setEnabled(enabled);
	}

	private void handleEvent(final Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return;
		}

		switch (intent.getAction()) {
			case C.Event.INSTALLED: {
				showProgress(getString(R.string.progress_init, BuildConfig.VERSION_NAME));
				break;
			}

			case C.Event.LAUNCHED: {
				showProgress(getString(R.string.progress_launching));
				/*if (intent.hasExtra(C.Key.STATION_LIST)) {
					List<IStation> s = convert(intent.getParcelableArrayExtra(C.Key.STATION_LIST));
					Log.i("MA", "StationList = " + s.size());

					mFrequencyInfo.notifyStationsLists(s);
				}*/
				break;
			}

			case C.Event.ENABLED: {
				hideProgress();
				setEnabledUi(true);
				setEnabledToggleButton(true);
				break;
			}

			case C.Event.FREQUENCY_SET: {
				hideProgress();

				final int kHz = intent.getIntExtra(C.Key.FREQUENCY, -1);
				mFrequencyInfo.setFrequency(kHz);
				String str = getString(R.string.player_event_frequency_changed, kHz / 1000f);
				mToast.text(str).show();

				mFrequencyInfo.setRdsPs("");
				mFrequencyInfo.setRdsRt("");
				mViewRssi.setText("?");
				break;
			}

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
				mViewRssi.setText(getString(R.string.main_rssi_db, rssi));
				setRssiIcon(rssi);
				break;
			}

			case C.Event.UPDATE_STEREO: {
				boolean isStereo = intent.getBooleanExtra(C.Key.STEREO_MODE, false);
				mViewStereoMode.setImageResource(isStereo ? R.drawable.ic_stereo : R.drawable.ic_mono);
				break;
			}

			case C.Event.DISABLED: {
				setEnabledUi(false);
				hideProgress();
				setEnabledToggleButton(true);
				break;
			}

			case C.Event.HW_SEEK_COMPLETE:
			case C.Event.JUMP_COMPLETE: {
				hideProgress();
				break;
			}

			case C.Event.RECORD_STARTED: {
				setShowRecordingPanel(true);
				if (mMenu != null) {
					mMenu.findItem(R.id.menu_record).setEnabled(false);
				}
				break;
			}

			case C.Event.RECORD_TIME_UPDATE: {
				mRecordDuration.setText(getTimeStringBySeconds(intent.getIntExtra(C.Key.DURATION, 0)));
				break;
			}

			case C.Event.RECORD_ENDED: {
				setShowRecordingPanel(false);
				if (mMenu != null) {
					mMenu.findItem(R.id.menu_record).setEnabled(true);
				}
				break;
			}

		}

		updateControlButtonState();
	}

	private void updateControlButtonState() {
		final int stage = mRadioController.getState().getInt(C.Key.STAGE);
		final boolean enabled = stage == C.FMStage.ENABLED;
		mCtlToggle.setImageResource(enabled ? R.drawable.ic_stop : R.drawable.ic_play);
	}

	private void setEnabledUi(final boolean state) {
		@IdRes int[] ids = {
				R.id.ctl_go_down,
				R.id.ctl_go_up,
				R.id.ctl_seek_down,
				R.id.ctl_seek_up,
				R.id.frequency_mhz,
				R.id.frequency_mhz_reflection,
				R.id.frequency_ps,
				R.id.frequency_rt,
				R.id.frequency_seek,
				R.id.rssi_icon,
				R.id.rssi_value,
				R.id.record_icon,
				R.id.record_duration,
				R.id.stereo_mono,
				R.id.favorite_list
		};

		for (int id : ids) {
			View v = findViewById(id);
			v.setAlpha(state ? 1f : .5f);
			v.setEnabled(state);
		}

		if (mMenu != null) {
			mMenu.findItem(R.id.menu_stop).setEnabled(state);
			mMenu.findItem(R.id.menu_record).setEnabled(state);
		}
	}

	private static final @IdRes	int[] SIGNAL_RES_ID = {
			R.drawable.ic_signal_0,
			R.drawable.ic_signal_1,
			R.drawable.ic_signal_2,
			R.drawable.ic_signal_3,
			R.drawable.ic_signal_4
	};

	private static final int[] SIGNAL_THRESHOLD = {
			-85,
			-75,
			-65,
			-55,
			-10
	};

	private void setRssiIcon(final int rssi) {
		for (int i = 0; i < SIGNAL_RES_ID.length; ++i) {
			if (rssi < SIGNAL_THRESHOLD[i]) {
				mViewRssiIcon.setImageResource(SIGNAL_RES_ID[i]);
				break;
			}
		}
	}

	private void setShowRecordingPanel(final boolean state) {
		final int visibility = state ? View.VISIBLE : View.GONE;
		mRecordDuration.setVisibility(visibility);
		mRecordIcon.setVisibility(visibility);
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
