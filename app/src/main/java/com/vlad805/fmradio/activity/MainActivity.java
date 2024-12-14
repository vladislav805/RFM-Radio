package com.vlad805.fmradio.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.RadioStateUpdater;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.preferences.LaunchCounter;
import com.vlad805.fmradio.service.FMService;
import com.vlad805.fmradio.view.FavoritesPanelView;
import com.vlad805.fmradio.view.RadioUIView;
import net.grandcentrix.tray.AppPreferences;

import static com.vlad805.fmradio.Utils.alert;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

@SuppressLint("NonConstantResourceId")
public class MainActivity extends AppCompatActivity implements View.OnClickListener, RadioStateUpdater.TunerStateListener {
    private ProgressDialog mProgress;
    private Toast mToast;
    private RadioUIView mFrequencyInfo;
    private FavoritesPanelView mFavoriteList;

    private RadioController mRadioController;

    private ImageButton mCtlToggle;

    private TextView mViewRssi;
    private ImageView mViewRssiIcon;
    private ImageView mViewStereoMode;

    private ImageView mRecordIcon;
    private TextView mRecordDuration;

    private RadioState mLastState;

    private Menu mMenu;

    private AppPreferences mPreferences;

    private static final int REQUEST_CODE_FAVORITES_OPENED = 1048;
    private static final int REQUEST_CODE_SETTINGS_CHANGED = 1050;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToast = Toast.create(this);

        mPreferences = new AppPreferences(this);

        setSupportActionBar(findViewById(R.id.main_toolbar));

        mRadioController = new RadioController(this);
        mRadioController.requestForCurrentState(this);
        mRadioController.registerForUpdates(this);
        mRadioController.setPowerMode(PowerMode.NORMAL);

        initUserInterface();
        initLogic();

        if (LaunchCounter.checkForDonation(mPreferences)) {
            new Handler().postDelayed(this::showDonationDialog, 6000);
        }
    }

    /**
     * Activity handle, UI setup
     */
    private void initUserInterface() {
        mFrequencyInfo = findViewById(R.id.frequency_info);

        mFavoriteList = findViewById(R.id.favorite_list);

        mRecordIcon = findViewById(R.id.record_icon);
        mRecordDuration = findViewById(R.id.record_duration);

        mRecordIcon.setOnClickListener(this);

        mFrequencyInfo.setFrequency(Storage.getInstance(this).getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY));

        initClickableButtons();
    }

    /**
     * Initialize connection and service
     */
    private void initLogic() {
        final boolean needStartup = Storage.getPrefBoolean(this, C.PrefKey.APP_AUTO_STARTUP, C.PrefDefaultValue.APP_AUTO_STARTUP);

        if (needStartup) {
            mRadioController.setup();
        } else {
            setEnabledUi(false);
        }

        mFrequencyInfo.setRadioController(mRadioController);
    }

    private void showProgress(final String text) {
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
        super.onDestroy();

        mRadioController.unregisterForUpdates();

        if (Storage.getPrefBoolean(this, C.PrefKey.TUNER_POWER_MODE, false)) {
            mRadioController.setPowerMode(PowerMode.LOW);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_FAVORITES_OPENED: {
                if (resultCode == Activity.RESULT_OK && data.getBooleanExtra("changed", false)) {
                    mFavoriteList.reload(true);
                }
                break;
            }

            case REQUEST_CODE_SETTINGS_CHANGED: {
                if (resultCode == RESULT_OK && data.getBooleanExtra("changed", false)) {
                    alert(
                            this,
                            R.string.main_warning_pref_changed_title,
                            R.string.main_warning_pref_changed_content,
                            android.R.string.ok
                    );
                }
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

        final int[] ids = {
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
    public void onClick(final View view) {
        switch (view.getId()) {
            // Main play/stop button
            case R.id.ctl_toggle: {
                switch (mRadioController.getState().getStatus()) {
                    case IDLE: {
                        mRadioController.setup();
                        break;
                    }

                    case INSTALLED:
                    case LAUNCHED: {
                        mRadioController.enable();
                        break;
                    }

                    case ENABLED: {
                        mRadioController.disable();
                        break;
                    }
                }
                break;
            }

            case R.id.ctl_go_down: {
                mRadioController.jump(Direction.DOWN);
                break;
            }

            case R.id.ctl_go_up: {
                mRadioController.jump(Direction.UP);
                break;
            }

            case R.id.ctl_seek_down: {
                mRadioController.hwSeek(Direction.DOWN);
                showProgress(getString(R.string.progress_searching));
                break;
            }

            case R.id.ctl_seek_up: {
                mRadioController.hwSeek(Direction.UP);
                showProgress(getString(R.string.progress_searching));
                break;
            }

            case R.id.favorite_button: {
                startActivityForResult(new Intent(this, FavoritesListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);
                break;
            }

            case R.id.record_icon: {
                mRadioController.record(false);
                break;
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        mMenu = menu;
        if (mLastState != null) {
            updateMenuState();
        }
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
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS_CHANGED);
                break;

            case R.id.menu_record:
                mRadioController.record(true);
                break;

            case R.id.menu_speaker: {
                startService(new Intent(this, FMService.class).setAction(C.Command.SPEAKER_STATE));
                break;
            }
        }

        return super.onOptionsItemSelected(item);
    }

    private void setEnabledToggleButton(final boolean enabled) {
        mCtlToggle.setEnabled(enabled);
    }

    /**
     * Called when the state of the tuner changes
     * @param state State object
     * @param mode Bitmask what was changed
     */
    @Override
    public void onStateUpdated(final RadioState state, final int mode) {
        mLastState = state;

        if ((mode & RadioStateUpdater.SET_STATUS) > 0) {
            handleChangingState(state.getStatus());
            return;
        }

        if ((mode & RadioStateUpdater.SET_FREQUENCY) > 0) {
            hideProgress();

            mFrequencyInfo.setFrequency(state.getFrequency());

            final String str = getString(R.string.player_event_frequency_changed, state.getFrequency() / 1000f);
            mToast.text(str).show();

            mFrequencyInfo.setRadioState(state);
            mViewRssi.setText("...");
        }

        if (
            (mode & RadioStateUpdater.SET_PS) > 0 ||
            (mode & RadioStateUpdater.SET_RT) > 0 ||
            (mode & RadioStateUpdater.SET_PTY) > 0
        ) {
            mFrequencyInfo.setRadioState(state);
        }

        if ((mode & RadioStateUpdater.SET_RSSI) > 0) {
            setRssiIcon(state.getRssi());
            mViewRssi.setText(getString(R.string.main_rssi_db, state.getRssi()));
        }

        if ((mode & RadioStateUpdater.SET_STEREO) > 0) {
            mViewStereoMode.setImageResource(state.isStereo() ? R.drawable.ic_stereo : R.drawable.ic_mono);
        }

        if ((mode & RadioStateUpdater.SET_RECORDING) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            final boolean isRecording = state.isRecording();

            setShowRecordingPanel(isRecording);
            if (mMenu != null) {
                mMenu.findItem(R.id.menu_record).setEnabled(!isRecording);
            }

            if (isRecording) {
                mRecordDuration.setText(getTimeStringBySeconds(state.getRecordingDuration()));
            }
        }

        if ((mode & RadioStateUpdater.SET_SPEAKER) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            final boolean isSpeaker = state.isForceSpeaker();

            if (mMenu != null) {
                mMenu.findItem(R.id.menu_speaker).setChecked(isSpeaker);
            }
        }
    }

    private void handleChangingState(final TunerStatus status) {
        switch (status) {
            case IDLE: {
                hideProgress();
                setEnabledUi(false);
                setPlayingMainPlayButton(false);
                setEnabledToggleButton(true);
                break;
            }

            case INSTALLING: {
                showProgress(getString(R.string.progress_installing));
                setEnabledToggleButton(false);
                break;
            }

            case INSTALLED:
            case LAUNCHED: {
                showProgress(null);
                break;
            }

            case LAUNCHING: {
                showProgress(getString(R.string.progress_launching));
                break;
            }

            case LAUNCH_FAILED: {
                hideProgress();

                Utils.alert(
                        this,
                        R.string.main_error_launching_title,
                        R.string.main_error_launching,
                        android.R.string.ok
                );
                break;
            }

            case ENABLING: {
                showProgress(getString(R.string.progress_starting));
                break;
            }

            case ENABLED: {
                hideProgress();
                setEnabledUi(true);
                setEnabledToggleButton(true);
                setPlayingMainPlayButton(true);
                break;
            }
        }
    }

    private void setPlayingMainPlayButton(final boolean isPlay) {
        mCtlToggle.setImageResource(isPlay ? R.drawable.ic_stop : R.drawable.ic_play);
    }

    /**
     * Enables or disables the entire interface
     * @param state True if enabled, false otherwise
     */
    private void setEnabledUi(final boolean state) {
        final @IdRes int[] ids = {
                R.id.ctl_go_down,
                R.id.ctl_go_up,
                R.id.ctl_seek_down,
                R.id.ctl_seek_up,
                R.id.frequency_mhz,
                R.id.frequency_ps,
                R.id.frequency_rt,
                R.id.frequency_pty,
                R.id.frequency_seek,
                R.id.rssi_icon,
                R.id.rssi_value,
                R.id.record_icon,
                R.id.record_duration,
                R.id.stereo_mono,
                R.id.favorite_list
        };

        for (final int id : ids) {
            final View v = findViewById(id);
            v.setAlpha(state ? 1f : .5f);
            v.setEnabled(state);
        }

        updateMenuState();
    }

    private void updateMenuState() {
        if (mMenu == null) {
            return;
        }

        final boolean state = TunerStatus.ENABLED.equals(mLastState.getStatus());

        mMenu.findItem(R.id.menu_stop).setEnabled(state);
        mMenu.findItem(R.id.menu_record).setEnabled(state);
        mMenu.findItem(R.id.menu_speaker).setEnabled(state);
        mMenu.findItem(R.id.menu_speaker).setChecked(mLastState.isForceSpeaker());
    }

    private static final int[] SIGNAL_RES_ID = {
            R.drawable.ic_signal_0,
            R.drawable.ic_signal_1,
            R.drawable.ic_signal_2,
            R.drawable.ic_signal_3,
            R.drawable.ic_signal_4,
            R.drawable.ic_signal_5,
            R.drawable.ic_signal_6,
    };

    private static final int[] SIGNAL_THRESHOLD = {
            -110,
            -98,
            -86,
            -74,
            -62,
            -50,
            -10,
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

    private void showDonationDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.donation_window_title)
                .setMessage(R.string.donation_window_message)
                .setPositiveButton(R.string.donation_window_donate, (dialog, which) -> {
                    dialog.dismiss();
                    final Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("https://rfm.velu.ga/donate?ref=app&hl=" + Utils.getCountryISO(this)));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.donation_window_later, (dialog, which) -> dialog.dismiss())
                .setNeutralButton(R.string.donation_window_never, (dialog, which) -> {
                    dialog.dismiss();
                    LaunchCounter.setDonationNeverShow(mPreferences);
                })
                .setIcon(R.mipmap.ic_launcher)
                .create()
                .show();
    }
}
