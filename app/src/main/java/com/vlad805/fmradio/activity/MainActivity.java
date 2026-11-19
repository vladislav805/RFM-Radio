package com.vlad805.fmradio.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioController;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.RadioStateUpdater;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.preferences.BandUtils;
import com.vlad805.fmradio.service.FMService;
import com.vlad805.fmradio.service.recording.RecordingStorage;
import com.vlad805.fmradio.view.FavoritesView;
import com.vlad805.fmradio.view.FrequencyDialView;
import com.vlad805.fmradio.view.RadioUIView;
import com.vlad805.fmradio.view.SignalStrengthView;

import net.grandcentrix.tray.AppPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.vlad805.fmradio.Utils.alert;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

@SuppressLint("NonConstantResourceId")
public class MainActivity extends AppCompatActivity implements View.OnClickListener, RadioStateUpdater.TunerStateListener {
    private Toast mToast;
    private RadioUIView mFrequencyInfo;
    private FrequencyDialView mSeek;
    private FavoritesView mFavoritesView;

    private RadioController mRadioController;
    private FavoriteController mFavoriteController;
    private ProgressDialog mSearchProgress;

    private AppPreferences mPreferences;

    private ImageButton mCtlToggle;
    private TextView mViewStereoMode;
    private TextView mAfBadge;
    private SignalStrengthView mSignalStrength;

    private View mBtnRecord;
    private ImageView mRecordDot;
    private TextView mRecordDuration;

    private RadioState mLastState;

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_SCAN = 2;
    private static final int MENU_SPEAKER = 3;
    private static final int MENU_ABOUT = 4;

    private static final int REQUEST_CODE_FAVORITES_OPENED = 1048;
    private static final int REQUEST_CODE_SETTINGS_CHANGED = 1050;
    private static final int REQUEST_CODE_RECORD_PERMISSIONS = 1051;
    private static final int REQUEST_CODE_PLAYBACK_PERMISSION = 1052;
    private boolean mPendingRecordStart = false;
    private boolean mRecordPermissionsRequested = false;
    private boolean mPendingPlaybackStart = false;
    private boolean mPlaybackPermissionRequested = false;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private boolean mRecordTickerRunning = false;
    private boolean mRecordDotDimmed = false;

    private final Runnable mRecordTicker = new Runnable() {
        @Override
        public void run() {
            if (mLastState == null || !mLastState.isRecording()) {
                mRecordTickerRunning = false;
                if (mRecordDot != null) {
                    mRecordDot.setAlpha(1f);
                }
                return;
            }

            final long durationSec = Math.max(0L, mLastState.getRecordingDuration());
            if (mRecordDuration != null) {
                mRecordDuration.setText(getTimeStringBySeconds(durationSec));
            }
            if (mRecordDot != null) {
                mRecordDotDimmed = !mRecordDotDimmed;
                mRecordDot.setAlpha(mRecordDotDimmed ? 0.3f : 1.0f);
            }
            mUiHandler.postDelayed(this, 500L);
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Utils.isRootEnvironmentAvailable()) {
            startActivity(StartupBlockedActivity.forMissingRoot(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        if (!TunerDriverDetector.isDeviceSupported()) {
            startActivity(StartupBlockedActivity.forUnsupportedDevice(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        mToast = Toast.create(this);

        mPreferences = new AppPreferences(this);
        mRadioController = new RadioController(this);
        mFavoriteController = new FavoriteController(this);
        mRadioController.requestForCurrentState(this);
        mRadioController.registerForUpdates(this);
        mRadioController.setPowerMode(PowerMode.NORMAL);

        initUserInterface();
        initLogic();
    }

    /**
     * Activity handle, UI setup
     */
    private void initUserInterface() {
        mFrequencyInfo = findViewById(R.id.frequency_info);

        mFavoritesView = findViewById(R.id.favorite_list);
        mFavoritesView.setOnFavoritesChangedListener(this::updateFavoriteFrequencyMarkers);
        mFavoritesView.setOnErrorListener(message -> mToast.text(message).show());

        mSeek = findViewById(R.id.frequency_seek);

        final int kHz = Storage.getInstance(this).getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
        mFrequencyInfo.setFrequency(kHz);
        mSeek.setFrequency(kHz);
        mFavoritesView.setActiveFrequency(kHz);
        mFavoritesView.reload(false);
        updateFavoriteFrequencyMarkers();

        initClickableButtons();
    }

    /**
     * Initialize connection and service
     */
    private void initLogic() {
        final boolean needStartup = Storage.getPrefBoolean(this, C.PrefKey.APP_AUTO_STARTUP, C.PrefDefaultValue.APP_AUTO_STARTUP);

        if (needStartup) {
            if (ensurePlaybackPermissionForLegacyAudio()) {
                startRadio();
            }
        } else {
            setEnabledUi(false);
            mFrequencyInfo.showStatus(R.string.status_tuner_stopped);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        reloadPreferences();
        mFavoritesView.reload(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mUiHandler.removeCallbacks(mRecordTicker);
        mRecordTickerRunning = false;

        if (mRadioController != null) {
            mRadioController.unregisterForUpdates();
        }

        if (mRadioController != null && Storage.getPrefBoolean(this, C.PrefKey.TUNER_POWER_MODE, false)) {
            mRadioController.setPowerMode(PowerMode.LOW);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_FAVORITES_OPENED: {
                if (resultCode == Activity.RESULT_OK && data != null && data.getBooleanExtra("changed", false)) {
                    mFavoritesView.reload(true);
                    updateFavoriteFrequencyMarkers();
                }
                break;
            }

            case REQUEST_CODE_SETTINGS_CHANGED: {
                if (resultCode == RESULT_OK && data != null && data.getBooleanExtra("changed", false)) {
                    alert(
                            this,
                            R.string.main_warning_pref_changed_title,
                            R.string.main_warning_pref_changed_content,
                            android.R.string.ok
                    );
                }
                break;
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == REQUEST_CODE_RECORD_PERMISSIONS) {
            final boolean granted = hasRecordingPermissions();
            if (granted && mPendingRecordStart) {
                mRadioController.record(true);
            } else if (mPendingRecordStart) {
                mToast.text(getString(R.string.record_permissions_required)).show();
            }
            mPendingRecordStart = false;
        }

        if (requestCode == REQUEST_CODE_PLAYBACK_PERMISSION) {
            final boolean granted = hasPlaybackPermission();
            if (granted && mPendingPlaybackStart) {
                startRadio();
            } else if (mPendingPlaybackStart) {
                mToast.text(getString(R.string.playback_permission_required)).show();
            }
            mPendingPlaybackStart = false;
        }

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Setup clickable buttons
     */
    private void initClickableButtons() {
        mCtlToggle = findViewById(R.id.ctl_toggle);
        mViewStereoMode = findViewById(R.id.stereo_mono);
        if (TunerDriverDetector.isExynos(this)) {
            mViewStereoMode.setVisibility(View.GONE);
        }
        mAfBadge = findViewById(R.id.af_badge);
        mSignalStrength = findViewById(R.id.signal_strength);

        mBtnRecord = findViewById(R.id.btn_record);
        mRecordDot = findViewById(R.id.record_dot);
        mRecordDuration = findViewById(R.id.record_duration_text);

        final int[] ids = {
                R.id.ctl_toggle,
                R.id.ctl_go_down,
                R.id.ctl_go_up,
                R.id.ctl_seek_down,
                R.id.ctl_seek_up,
                R.id.favorite_button,
                R.id.btn_record,
                R.id.btn_settings_menu,
                R.id.af_badge
        };

        for (final int id : ids) {
            final View view = findViewById(id);
            if (view != null) {
                view.setOnClickListener(this);
            }
        }

        updateAfUi(null);
        updateStereoUi(false);
    }

    @Override
    public void onClick(final View view) {
        final int id = view.getId();

        if (id == R.id.ctl_toggle) {
            // Main power toggle button
            switch (mRadioController.getState().getStatus()) {
                case IDLE: {
                    if (ensurePlaybackPermissionForLegacyAudio()) {
                        startRadio();
                    }
                    break;
                }

                case ENABLED: {
                    setEnabledToggleButton(false);
                    mRadioController.disable();
                    break;
                }
            }
        } else if (id == R.id.ctl_go_down) {
            mRadioController.jump(Direction.DOWN);
        } else if (id == R.id.ctl_go_up) {
            mRadioController.jump(Direction.UP);
        } else if (id == R.id.ctl_seek_down) {
            mRadioController.hwSeek(Direction.DOWN);
            mFrequencyInfo.showStatus(R.string.status_searching_station);
        } else if (id == R.id.ctl_seek_up) {
            mRadioController.hwSeek(Direction.UP);
            mFrequencyInfo.showStatus(R.string.status_searching_station);
        } else if (id == R.id.favorite_button) {
            startActivityForResult(new Intent(this, FavoriteListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);
        } else if (id == R.id.btn_record) {
            toggleRecording();
        } else if (id == R.id.btn_settings_menu) {
            showSettingsPopupMenu(view);
        } else if (id == R.id.af_badge) {
            showAfFrequenciesDialog();
        }
    }

    private void toggleRecording() {
        if (mRadioController.getState().getStatus() != TunerStatus.ENABLED) {
            return;
        }

        if (mRadioController.getState().isRecording()) {
            mRadioController.record(false);
            return;
        }

        if (hasRecordingPermissions()) {
            mRadioController.record(true);
        } else if (shouldOpenRecordingPermissionSettings()) {
            showRecordingPermissionSettingsDialog();
        } else {
            mPendingRecordStart = true;
            mRecordPermissionsRequested = true;

            final String[] permissions = !RecordingStorage.requiresStoragePermission(this)
                    ? new String[]{
                            Manifest.permission.RECORD_AUDIO
                    }
                    : new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    };

            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_RECORD_PERMISSIONS);
        }
    }

    private void showSettingsPopupMenu(final View anchor) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        final Menu menu = popup.getMenu();

        menu.add(0, MENU_SETTINGS, 1, R.string.menu_settings);
        final boolean isEnabled = mLastState != null && mLastState.getStatus() == TunerStatus.ENABLED;
        menu.add(0, MENU_SCAN, 2, R.string.menu_scan_stations).setEnabled(isEnabled);
        menu.add(0, MENU_SPEAKER, 3, R.string.menu_speaker)
                .setCheckable(true)
                .setChecked(mLastState != null && mLastState.isForceSpeaker())
                .setEnabled(isEnabled);
        menu.add(0, MENU_ABOUT, 4, R.string.menu_about);

        popup.setOnMenuItemClickListener(item -> {
            final int itemId = item.getItemId();
            if (itemId == MENU_SETTINGS) {
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS_CHANGED);
                return true;
            } else if (itemId == MENU_SCAN) {
                showSearchDialog();
                return true;
            } else if (itemId == MENU_SPEAKER) {
                startService(new Intent(this, FMService.class).setAction(C.Command.SPEAKER_STATE));
                return true;
            } else if (itemId == MENU_ABOUT) {
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void showAfFrequenciesDialog() {
        final int[] rawAf = mLastState != null ? mLastState.getAf() : null;
        if (rawAf == null || rawAf.length == 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.rds_af_dialog_title)
                    .setMessage(R.string.rds_af_dialog_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final int[] sortedAf = Arrays.copyOf(rawAf, rawAf.length);
        Arrays.sort(sortedAf);

        final int spacingPref = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);
        final int precision = spacingPref == BandUtils.SPACING_50kHz ? 2 : 1;

        final String[] labels = new String[sortedAf.length];
        for (int i = 0; i < sortedAf.length; i++) {
            labels[i] = Utils.getMHz(sortedAf[i], precision).trim() + " MHz";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.rds_af_dialog_title)
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < sortedAf.length && mRadioController.getState().getStatus() == TunerStatus.ENABLED) {
                        mRadioController.setFrequency(sortedAf[which]);
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void startRadio() {
        setEnabledToggleButton(false);
        mRadioController.setup();
    }

    private void showSearchDialog() {
        mRadioController.getCurrentState(state -> {
            if (state.getStatus() != TunerStatus.ENABLED) {
                mToast.text(R.string.scan_stations_error_tuner_not_enabled).show();
                return;
            }

            new AlertDialog.Builder(this)
                    .setTitle(R.string.scan_stations_confirm_title)
                    .setMessage(R.string.scan_stations_confirm_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.scan_stations_confirm_continue, (dialog, buttonId) -> startSearch())
                    .setNegativeButton(R.string.scan_stations_confirm_discard, null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
        });
    }

    private void startSearch() {
        mRadioController.hwSearch();

        mSearchProgress = ProgressDialog.create(this)
                .text(R.string.scan_stations_progress)
                .negativeButton(android.R.string.cancel, (dialog, buttonId) -> {
                    mRadioController.cancelHwSearch();
                    unregisterSearchReceiver();
                    mSearchProgress = null;
                })
                .show();

        final IntentFilter searchFilter = new IntentFilter(C.Event.HW_SEARCH_DONE);
        searchFilter.addAction(C.Event.HW_SEARCH_FAILED);
        searchFilter.addAction(C.Event.ERROR_OCCURRED);
        Utils.registerAppReceiver(this, mSearchDone, searchFilter);
    }

    private void unregisterSearchReceiver() {
        try {
            unregisterReceiver(mSearchDone);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private final BroadcastReceiver mSearchDone = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }

            final String action = intent.getAction();
            unregisterSearchReceiver();

            if (mSearchProgress != null) {
                mSearchProgress.hide();
                mSearchProgress = null;
            }

            if (C.Event.HW_SEARCH_FAILED.equals(action) || C.Event.ERROR_OCCURRED.equals(action)) {
                mToast.text(R.string.scan_stations_failed).show();
                return;
            }

            final int[] frequencies = intent.getIntArrayExtra(C.Key.STATION_LIST);
            if (frequencies == null) {
                mToast.text(R.string.scan_stations_failed).show();
                return;
            }

            mFavoriteController.reload();
            final List<FavoriteStation> stations = mFavoriteController.getStationsInCurrentList();
            stations.clear();

            if (frequencies != null) {
                for (final int kHz : frequencies) {
                    stations.add(new FavoriteStation(kHz, ""));
                }
            }

            mFavoriteController.save();
            Utils.sendAppBroadcast(MainActivity.this, new Intent(C.Event.FAVORITE_LIST_CHANGED));
            mFavoritesView.reload(true);
            updateFavoriteFrequencyMarkers();
        }
    };

    private void setEnabledToggleButton(final boolean enabled) {
        mCtlToggle.setEnabled(enabled);
        mCtlToggle.setAlpha(enabled ? 1f : .45f);
    }

    private boolean hasRecordingPermissions() {
        final boolean hasRecordAudio =
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!RecordingStorage.requiresStoragePermission(this)) {
            return hasRecordAudio;
        }

        return hasRecordAudio
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPlaybackPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsPlaybackPermissionForLegacyAudio() {
        return (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
            TunerDriverDetector.getTunerDriver() == TunerDriver.V4L2_QCOM
        );
    }

    private boolean ensurePlaybackPermissionForLegacyAudio() {
        if (!needsPlaybackPermissionForLegacyAudio() || hasPlaybackPermission()) {
            return true;
        }

        if (shouldOpenPlaybackPermissionSettings()) {
            showPlaybackPermissionSettingsDialog();
        } else {
            mPendingPlaybackStart = true;
            mPlaybackPermissionRequested = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    REQUEST_CODE_PLAYBACK_PERMISSION
            );
        }

        return false;
    }

    private boolean shouldOpenRecordingPermissionSettings() {
        if (!mRecordPermissionsRequested) {
            return false;
        }

        if (!RecordingStorage.requiresStoragePermission(this)) {
            return !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO);
        }

        return !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void showRecordingPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.record_permissions_required_title)
                .setMessage(R.string.record_permissions_required)
                .setPositiveButton(R.string.record_permissions_open_settings, (DialogInterface dialog, int which) -> openAppSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean shouldOpenPlaybackPermissionSettings() {
        if (!mPlaybackPermissionRequested) {
            return false;
        }

        return !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO);
    }

    private void showPlaybackPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.playback_permission_required_title)
                .setMessage(R.string.playback_permission_required)
                .setPositiveButton(R.string.record_permissions_open_settings, (DialogInterface dialog, int which) -> openAppSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openAppSettings() {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void reloadPreferences() {
        final int regionPref = mPreferences.getInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
        final int spacingPref = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

        final BandUtils.BandLimit bandLimit = BandUtils.getBandLimit(regionPref, TunerDriverDetector.getTunerDriver(this));
        final int spacing = BandUtils.getSpacing(spacingPref);

        mSeek.setMinMaxValue(bandLimit.lower, bandLimit.upper, spacing);
        mSeek.setOnFrequencyChangeListener(mOnFrequencyChanged);
        updateFavoriteFrequencyMarkers();
    }

    private void updateFavoriteFrequencyMarkers() {
        if (mFavoritesView == null || mSeek == null) {
            return;
        }

        final Set<Integer> frequencies = new HashSet<>();
        for (final FavoriteStation station : mFavoritesView.getStationsInCurrentList()) {
            frequencies.add(station.getFrequency());
        }
        mSeek.setFavoriteFrequencies(frequencies);
    }

    private final FrequencyDialView.OnFrequencyChangedListener mOnFrequencyChanged = new FrequencyDialView.OnFrequencyChangedListener() {
        @Override
        public void onPreview(final int kHz) {
            mFrequencyInfo.setFrequency(kHz);
            mFavoritesView.setActiveFrequency(kHz);
        }

        @Override
        public void onChanged(final int kHz) {
            mFrequencyInfo.setFrequency(kHz);
            mFavoritesView.setActiveFrequency(kHz);

            if (mRadioController.getState().getFrequency() == kHz) {
                return;
            }

            mRadioController.setFrequency(kHz);
        }
    };

    /**
     * Called when the state of the tuner changes
     * @param state State object
     * @param mode Bitmask what was changed
     */
    @Override
    public void onStateUpdated(final RadioState state, final int mode) {
        mLastState = state;

        if ((mode & (RadioStateUpdater.SET_RMSSI | RadioStateUpdater.SET_SINR)) > 0) {
            final boolean available = (
                    state.getRmssi() != RadioState.RMSSI_UNKNOWN ||
                    state.getSinr() != RadioState.SINR_UNKNOWN
            );
            if (available) {
                mSignalStrength.setMetrics(state.getRmssi(), state.getSinr());
            } else {
                mSignalStrength.clear();
            }
        }

        if ((mode & RadioStateUpdater.SET_AF) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            updateAfUi(state.getAf());
        }

        if ((mode & RadioStateUpdater.SET_STATUS) > 0) {
            handleChangingState(state.getStatus());
            if ((mode & RadioStateUpdater.SET_INITIAL) == 0 || state.getStatus() != TunerStatus.ENABLED) {
                return;
            }
        }

        if ((mode & RadioStateUpdater.SET_FREQUENCY) > 0) {
            mFrequencyInfo.hideStatus();
            if (!mSeek.isUserInteracting()) {
                mFrequencyInfo.setFrequency(state.getFrequency());
                mSeek.setFrequency(state.getFrequency());
                mFavoritesView.setActiveFrequency(state.getFrequency());
            }
            mFrequencyInfo.setRadioState(state);
        }

        if (
            (mode & RadioStateUpdater.SET_PS) > 0 ||
            (mode & RadioStateUpdater.SET_PI) > 0 ||
            (mode & RadioStateUpdater.SET_RT) > 0 ||
            (mode & RadioStateUpdater.SET_PTY) > 0 ||
            (mode & RadioStateUpdater.SET_COUNTRY) > 0 ||
            (mode & RadioStateUpdater.SET_RT_PLUS) > 0
        ) {
            mFrequencyInfo.setRadioState(state);
        }

        if ((mode & RadioStateUpdater.SET_STEREO) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            updateStereoUi(state.isStereo());
        }

        if ((mode & RadioStateUpdater.SET_RECORDING) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            updateRecordingUi(state);
        }
    }

    private void updateStereoUi(final boolean isStereo) {
        if (mViewStereoMode == null) {
            return;
        }
        if (TunerDriverDetector.isExynos(this)) {
            mViewStereoMode.setVisibility(View.GONE);
            return;
        }
        mViewStereoMode.setVisibility(View.VISIBLE);
        mViewStereoMode.setText(isStereo ? R.string.stereo_mode_label : R.string.mono_mode_label);
        mViewStereoMode.setContentDescription(getString(
                isStereo ? R.string.stereo_mode_active : R.string.mono_mode_active
        ));
        mViewStereoMode.setBackgroundResource(isStereo ? R.drawable.bg_rfm_pill_active : R.drawable.bg_rfm_pill);
        mViewStereoMode.setTextColor(ContextCompat.getColor(
                this,
                isStereo ? R.color.rfm_accent : R.color.rfm_text_secondary
        ));
    }

    private void updateAfUi(final int[] af) {
        if (mAfBadge == null) {
            return;
        }
        final int count = af != null ? af.length : 0;
        if (count > 0) {
            mAfBadge.setText(String.format(Locale.US, getString(R.string.rds_af_count), count));
            mAfBadge.setBackgroundResource(R.drawable.bg_rfm_pill_active);
            mAfBadge.setTextColor(ContextCompat.getColor(this, R.color.rfm_accent));
        } else {
            mAfBadge.setText(R.string.rds_af_label);
            mAfBadge.setBackgroundResource(R.drawable.bg_rfm_pill);
            mAfBadge.setTextColor(ContextCompat.getColor(this, R.color.rfm_text_muted));
        }
    }

    private void handleChangingState(final TunerStatus status) {
        switch (status) {
            case IDLE: {
                setEnabledUi(false);
                mFrequencyInfo.clearMetadata();
                mFrequencyInfo.showStatus(R.string.status_tuner_stopped);
                updateAfUi(null);
                updateStereoUi(false);
                setPlayingMainPlayButton(false);
                setEnabledToggleButton(true);
                break;
            }

            case INSTALLING: {
                mFrequencyInfo.showStatus(R.string.status_installing);
                setEnabledToggleButton(false);
                break;
            }

            case INSTALLED:
            case LAUNCHED: {
                mFrequencyInfo.hideStatus();
                setEnabledToggleButton(false);
                break;
            }

            case LAUNCHING: {
                mFrequencyInfo.showStatus(R.string.status_launching);
                setEnabledToggleButton(false);
                break;
            }

            case ROOT_ACCESS_DENIED: {
                mFrequencyInfo.clearMetadata();
                mFrequencyInfo.showStatus(R.string.status_tuner_stopped);
                setEnabledToggleButton(false);

                Utils.alert(
                        this,
                        R.string.root_required_title,
                        R.string.root_access_denied_message,
                        android.R.string.ok
                );
                break;
            }

            case FATAL_ERROR: {
                mFrequencyInfo.clearMetadata();
                mFrequencyInfo.showStatus(R.string.status_tuner_stopped);
                setEnabledToggleButton(false);

                Utils.alert(
                        this,
                        R.string.main_error_launching_title,
                        R.string.main_error_launching,
                        android.R.string.ok
                );
                break;
            }

            case ENABLING: {
                mFrequencyInfo.hideStatus();
                setEnabledToggleButton(false);
                break;
            }

            case DISABLING: {
                setEnabledToggleButton(false);
                break;
            }

            case ENABLED: {
                setEnabledUi(true);
                mFrequencyInfo.hideStatus();
                setEnabledToggleButton(true);
                setPlayingMainPlayButton(true);
                break;
            }
        }
    }

    private void setPlayingMainPlayButton(final boolean isPlay) {
        mCtlToggle.setBackgroundResource(isPlay ? R.drawable.bg_rfm_power_active : R.drawable.bg_rfm_power_idle);
        ImageViewCompat.setImageTintList(
                mCtlToggle,
                ColorStateList.valueOf(ContextCompat.getColor(
                        this,
                        isPlay ? R.color.rfm_on_accent : R.color.rfm_accent
                ))
        );
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
                R.id.frequency_pi,
                R.id.frequency_rt,
                R.id.frequency_pty,
                R.id.frequency_seek,
                R.id.stereo_mono,
                R.id.af_badge,
                R.id.signal_strength,
                R.id.btn_record,
                R.id.favorite_list
        };

        for (final int id : ids) {
            final View v = findViewById(id);
            if (v != null) {
                v.setAlpha(state ? 1f : .45f);
                v.setEnabled(state);
            }
        }
    }

    private void updateRecordingUi(final RadioState state) {
        if (mBtnRecord == null || mRecordDuration == null) {
            return;
        }

        final boolean isRecording = state.isRecording();
        mBtnRecord.setBackgroundResource(isRecording ? R.drawable.bg_rfm_pill_record : R.drawable.bg_rfm_pill);
        mRecordDuration.setTextColor(ContextCompat.getColor(
                this,
                isRecording ? R.color.rfm_record : R.color.rfm_text_primary
        ));

        if (isRecording) {
            final long durationSec = Math.max(0L, state.getRecordingDuration());
            mRecordDuration.setText(getTimeStringBySeconds(durationSec));
            if (!mRecordTickerRunning) {
                mRecordTickerRunning = true;
                mUiHandler.post(mRecordTicker);
            }
        } else {
            mUiHandler.removeCallbacks(mRecordTicker);
            mRecordTickerRunning = false;
            mRecordDuration.setText(R.string.record_badge_idle);
            if (mRecordDot != null) {
                mRecordDot.setAlpha(1f);
            }
        }
    }
}
