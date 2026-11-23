package com.vlad805.fmradio.activity;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.EventListener;
import com.vlad805.fmradio.controller.FavoriteController;
import com.vlad805.fmradio.controller.RadioClient;
import com.vlad805.fmradio.controller.RadioState;
import com.vlad805.fmradio.controller.StateListener;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;
import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.helper.ProgressDialog;
import com.vlad805.fmradio.helper.Toast;
import com.vlad805.fmradio.helper.TunerDriverDetector;
import com.vlad805.fmradio.models.FavoriteStation;
import com.vlad805.fmradio.preferences.BandUtils;
import com.vlad805.fmradio.service.recording.RecordingStorage;
import com.vlad805.fmradio.view.FavoriteListsBottomSheet;
import com.vlad805.fmradio.view.FavoritesRecyclerView;
import com.vlad805.fmradio.view.FavoritesView;
import com.vlad805.fmradio.view.FrequencyDialView;
import com.vlad805.fmradio.view.RadioUIView;
import com.vlad805.fmradio.view.SignalStrengthView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.vlad805.fmradio.Utils.alert;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

@SuppressLint("NonConstantResourceId")
public class MainActivity extends AppCompatActivity implements View.OnClickListener, StateListener {
    private Toast mToast;
    private RadioUIView mFrequencyInfo;
    private FrequencyDialView mSeek;
    private FavoritesView mFavoritesView;

    private RadioClient mRadioClient;
    private FavoriteController mFavoriteController;
    private ProgressDialog mSearchProgress;
    private boolean mSearchActive = false;
    private boolean mAutoStartupChecked = false;
    private Storage mStorage;

    private MaterialButton mCtlToggle;
    private MaterialCardView mViewStereoModeCard;
    private TextView mViewStereoMode;
    private MaterialCardView mAfBadgeCard;
    private TextView mAfBadge;
    private SignalStrengthView mSignalStrength;

    private MaterialCardView mBtnRecord;
    private ImageView mRecordDot;
    private TextView mRecordDuration;

    private RadioState mLastState;

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_SPEAKER = 2;
    private static final int MENU_ABOUT = 3;

    private static final int REQUEST_CODE_SETTINGS_CHANGED = 1050;
    private static final int REQUEST_CODE_RECORD_PERMISSIONS = 1051;
    private static final int REQUEST_CODE_PLAYBACK_PERMISSION = 1052;
    private boolean mPendingRecordStart = false;
    private boolean mRecordPermissionsRequested = false;
    private boolean mPendingPlaybackStart = false;
    private boolean mPlaybackPermissionRequested = false;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private boolean mRecordTickerRunning = false;
    private ObjectAnimator mRecordPulseAnimator;
    private ValueAnimator mPowerColorAnimator;
    private Boolean mLastPowerPlaying;

    private final Runnable mRecordTicker = new Runnable() {
        @Override
        public void run() {
            if (mLastState == null || !mLastState.isRecording()) {
                mRecordTickerRunning = false;
                return;
            }

            final long durationSec = Math.max(0L, mLastState.getRecordingDuration());
            if (mRecordDuration != null) {
                mRecordDuration.setText(getTimeStringBySeconds(durationSec));
            }
            mUiHandler.postDelayed(this, 500L);
        }
    };

    private final OnBackPressedCallback mFavoritesEditBackCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (mFavoritesView != null && mFavoritesView.isEditMode()) {
                mFavoritesView.exitEditMode();
            }
        }
    };

    private final FavoriteController.OnFavoritesChangeListener mFavoritesListener =
            (currentListName, stations) -> updateFavoriteFrequencyMarkers(stations);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
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

        mStorage = Storage.get(this);
        mFavoriteController = FavoriteController.get(this);
        mRadioClient = new RadioClient(this, this, mEventListener);

        initUserInterface();
        getOnBackPressedDispatcher().addCallback(this, mFavoritesEditBackCallback);
        mFavoriteController.addOnChangeListener(mFavoritesListener);

        setEnabledUi(false);
        mFrequencyInfo.showStatus(R.string.status_tuner_stopped);
    }

    /**
     * Activity handle, UI setup
     */
    private void initUserInterface() {
        mFrequencyInfo = new RadioUIView(findViewById(R.id.main_root));

        mFavoritesView = findViewById(R.id.favorite_list);
        mFavoritesView.setOnFavoritesChangedListener(this::updateFavoriteFrequencyMarkers);
        mFavoritesView.setOnErrorListener(message -> mToast.text(message).show());
        mFavoritesView.setOnListSelectorClickListener(() -> FavoriteListsBottomSheet.show(getSupportFragmentManager()));
        mFavoritesView.setOnEditModeChangeListener(mFavoritesEditBackCallback::setEnabled);
        mFavoritesView.setOnScanClickListener(this::showSearchDialog);
        mFavoritesView.setOnStationActionListener(new FavoritesRecyclerView.OnStationActionListener() {
            @Override
            public void onStationSelected(@NonNull final FavoriteStation station) {
                mRadioClient.setFrequency(station.getFrequency());
            }

            @Override
            public void onAddCurrentStationRequested() {
                final RadioState state = mRadioClient.getState();
                mFavoritesView.promptAddStation(state.getFrequency(), state.getPs());
            }
        });

        mSeek = findViewById(R.id.frequency_seek);

        final int kHz = mStorage.getLastFrequency();
        mFrequencyInfo.setFrequency(kHz);
        mSeek.setFrequency(kHz);
        mFavoritesView.setActiveFrequency(kHz);
        mFavoritesView.reload(false);
        updateFavoriteFrequencyMarkers();

        initClickableButtons();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mRadioClient != null) {
            mRadioClient.connect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        reloadPreferences();
        mFavoritesView.reload(false);
    }

    @Override
    protected void onStop() {
        if (mSearchActive) {
            cancelActiveSearch();
        }
        if (mRadioClient != null) {
            if (mStorage.isLowPowerMode() && mRadioClient.getState().getStatus() == TunerStatus.ENABLED) {
                mRadioClient.setPowerMode(PowerMode.LOW);
            }
            mRadioClient.disconnect();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mSearchActive) {
            cancelActiveSearch();
        } else {
            dismissSearchProgress();
        }

        mUiHandler.removeCallbacks(mRecordTicker);
        mRecordTickerRunning = false;
        stopRecordPulseAnimation();
        if (mPowerColorAnimator != null) {
            mPowerColorAnimator.cancel();
            mPowerColorAnimator = null;
        }
        if (mFrequencyInfo != null) {
            mFrequencyInfo.release();
        }

        if (mFavoriteController != null) {
            mFavoriteController.removeOnChangeListener(mFavoritesListener);
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch (requestCode) {
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
                mRadioClient.startRecord();
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
        mViewStereoModeCard = findViewById(R.id.stereo_mono_card);
        mViewStereoMode = findViewById(R.id.stereo_mono);
        final TunerDriver driver = TunerDriverDetector.getTunerDriver(this);
        if (driver.hasKnownCapabilities() && !driver.supportsStereoDetection() && mViewStereoModeCard != null) {
            mViewStereoModeCard.setVisibility(View.GONE);
        }
        mAfBadgeCard = findViewById(R.id.af_badge_card);
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
                R.id.btn_record,
                R.id.btn_settings_menu,
                R.id.af_badge_card
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
            switch (mRadioClient.getState().getStatus()) {
                case IDLE: {
                    if (ensurePlaybackPermissionForLegacyAudio()) {
                        startRadio();
                    }
                    break;
                }

                case ENABLED: {
                    setEnabledToggleButton(false);
                    mRadioClient.disable();
                    break;
                }
            }
        } else if (id == R.id.ctl_go_down) {
            mRadioClient.jump(Direction.DOWN);
        } else if (id == R.id.ctl_go_up) {
            mRadioClient.jump(Direction.UP);
        } else if (id == R.id.ctl_seek_down) {
            mRadioClient.hwSeek(Direction.DOWN);
            mFrequencyInfo.showStatus(R.string.status_searching_station);
        } else if (id == R.id.ctl_seek_up) {
            mRadioClient.hwSeek(Direction.UP);
            mFrequencyInfo.showStatus(R.string.status_searching_station);
        } else if (id == R.id.btn_record) {
            toggleRecording();
        } else if (id == R.id.btn_settings_menu) {
            showSettingsPopupMenu(view);
        } else if (id == R.id.af_badge_card) {
            showAfFrequenciesDialog();
        }
    }

    private void toggleRecording() {
        if (mRadioClient.getState().getStatus() != TunerStatus.ENABLED) {
            return;
        }

        if (mRadioClient.getState().isRecording()) {
            mRadioClient.stopRecord();
            return;
        }

        if (hasRecordingPermissions()) {
            mRadioClient.startRecord();
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
        menu.add(0, MENU_SPEAKER, 2, R.string.menu_speaker)
                .setCheckable(true)
                .setChecked(mLastState != null && mLastState.isForceSpeaker())
                .setEnabled(isEnabled);
        menu.add(0, MENU_ABOUT, 3, R.string.menu_about);

        popup.setOnMenuItemClickListener(item -> {
            final int itemId = item.getItemId();
            if (itemId == MENU_SETTINGS) {
                startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS_CHANGED);
                return true;
            } else if (itemId == MENU_SPEAKER) {
                mRadioClient.toggleSpeaker();
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
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.rds_af_dialog_title)
                    .setMessage(R.string.rds_af_dialog_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        final int[] sortedAf = Arrays.copyOf(rawAf, rawAf.length);
        Arrays.sort(sortedAf);

        final int spacingPref = mStorage.getSpacing();
        final int precision = spacingPref == BandUtils.SPACING_50kHz ? 2 : 1;

        final String[] labels = new String[sortedAf.length];
        for (int i = 0; i < sortedAf.length; i++) {
            labels[i] = Utils.getMHz(sortedAf[i], precision).trim() + " MHz";
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rds_af_dialog_title)
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < sortedAf.length && mRadioClient.getState().getStatus() == TunerStatus.ENABLED) {
                        mRadioClient.setFrequency(sortedAf[which]);
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void startRadio() {
        setEnabledToggleButton(false);
        mRadioClient.enable();
    }

    private void showSearchDialog() {
        if (mRadioClient.getState().getStatus() != TunerStatus.ENABLED) {
            mToast.text(R.string.scan_stations_error_tuner_not_enabled).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scan_stations_confirm_title)
                .setMessage(R.string.scan_stations_confirm_message)
                .setCancelable(false)
                .setPositiveButton(R.string.scan_stations_confirm_continue, (dialog, buttonId) -> startSearch())
                .setNegativeButton(R.string.scan_stations_confirm_discard, null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void startSearch() {
        mSearchActive = true;
        mRadioClient.hwSearch();

        dismissSearchProgress();
        mSearchProgress = ProgressDialog.create(this)
                .title(R.string.scan_stations_title)
                .text(getString(R.string.scan_stations_progress_count, 0))
                .negativeButton(android.R.string.cancel, (dialog, buttonId) -> cancelActiveSearch())
                .show();
    }

    private void cancelActiveSearch() {
        mSearchActive = false;
        dismissSearchProgress();
        if (mRadioClient != null) {
            mRadioClient.cancelHwSearch();
        }
    }

    private void dismissSearchProgress() {
        if (mSearchProgress != null) {
            mSearchProgress.hide();
            mSearchProgress = null;
        }
    }

    private final EventListener mEventListener = new EventListener() {
        @Override
        public void onSearchProgress(final int count) {
            if (mSearchActive && mSearchProgress != null && count >= 0) {
                mSearchProgress.text(getString(R.string.scan_stations_progress_count, count));
            }
        }

        @Override
        public void onSearchDone(@NonNull final int[] frequencies) {
            if (!mSearchActive) {
                return;
            }
            mSearchActive = false;
            dismissSearchProgress();

            final List<FavoriteStation> stations = new ArrayList<>(frequencies.length);
            for (final int kHz : frequencies) {
                stations.add(new FavoriteStation(kHz, ""));
            }

            mFavoriteController.replaceCurrentListStations(stations);
        }

        @Override
        public void onSearchFailed() {
            if (!mSearchActive) {
                return;
            }
            mSearchActive = false;
            dismissSearchProgress();
            mToast.text(R.string.scan_stations_failed).show();
        }

        @Override
        public void onTunerError(@NonNull final String message) {
            if (mSearchActive) {
                mSearchActive = false;
                dismissSearchProgress();
                mToast.text(R.string.scan_stations_failed).show();
            }
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
        new MaterialAlertDialogBuilder(this)
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
        new MaterialAlertDialogBuilder(this)
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
        final int regionPref = mStorage.getRegion();
        final int spacingPref = mStorage.getSpacing();

        final BandUtils.BandLimit bandLimit = BandUtils.getBandLimit(regionPref, TunerDriverDetector.getTunerDriver(this));
        final int spacing = BandUtils.getSpacing(spacingPref);

        mSeek.setMinMaxValue(bandLimit.lower, bandLimit.upper, spacing);
        mSeek.setOnFrequencyChangeListener(mOnFrequencyChanged);
        if (mFavoritesView != null) {
            mFavoritesView.setColumnCount(mStorage.getFavoritesColumns());
        }
        updateFavoriteFrequencyMarkers();
    }

    private void updateFavoriteFrequencyMarkers() {
        if (mFavoriteController == null) {
            return;
        }
        updateFavoriteFrequencyMarkers(mFavoriteController.getStationsInCurrentList());
    }

    private void updateFavoriteFrequencyMarkers(final List<FavoriteStation> stations) {
        if (mSeek == null || stations == null) {
            return;
        }

        final Set<Integer> frequencies = new HashSet<>();
        for (final FavoriteStation station : stations) {
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

            if (mRadioClient.getState().getFrequency() == kHz) {
                return;
            }

            mRadioClient.setFrequency(kHz);
        }
    };

    /**
     * Called when the state of the tuner changes
     * @param state State object
     * @param mode Bitmask what was changed
     */
    @Override
    public void onStateUpdated(@NonNull final RadioState state, final int mode) {
        mLastState = state;
        final boolean isInitial = (mode & RadioState.SET_INITIAL) != 0;

        if ((mode & (RadioState.SET_RMSSI | RadioState.SET_SINR)) != 0) {
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

        if ((mode & RadioState.SET_AF) != 0 || isInitial) {
            updateAfUi(state.getAf());
        }

        if ((mode & RadioState.SET_STATUS) != 0) {
            handleChangingState(state.getStatus());
            if (isInitial) {
                if (state.getStatus() == TunerStatus.ENABLED) {
                    mRadioClient.setPowerMode(PowerMode.NORMAL);
                }
                if (!mAutoStartupChecked) {
                    mAutoStartupChecked = true;
                    if (mStorage.isAutoStartupEnabled() && state.getStatus() == TunerStatus.IDLE) {
                        if (ensurePlaybackPermissionForLegacyAudio()) {
                            startRadio();
                        }
                    }
                }
            }
            if (!isInitial || state.getStatus() != TunerStatus.ENABLED) {
                if (isInitial && state.getFrequency() > 0 && !mSeek.isUserInteracting()) {
                    mFrequencyInfo.setFrequency(state.getFrequency());
                    mSeek.setFrequency(state.getFrequency());
                    mFavoritesView.setActiveFrequency(state.getFrequency());
                }
                if (isInitial || (mode & RadioState.SET_STEREO) != 0) {
                    updateStereoUi(state.isStereo());
                }
                if (isInitial || (mode & RadioState.SET_RECORDING) != 0) {
                    updateRecordingUi(state);
                }
                return;
            }
        }

        if ((mode & RadioState.SET_FREQUENCY) != 0) {
            if (state.getStatus() == TunerStatus.ENABLED) {
                mFrequencyInfo.hideStatus();
            }
            if (!mSeek.isUserInteracting() && state.getFrequency() > 0) {
                mFrequencyInfo.setFrequency(state.getFrequency());
                mSeek.setFrequency(state.getFrequency());
                mFavoritesView.setActiveFrequency(state.getFrequency());
            }
            mFrequencyInfo.setRadioState(state);
        }

        if (
            (mode & RadioState.SET_PS) != 0 ||
            (mode & RadioState.SET_PI) != 0 ||
            (mode & RadioState.SET_RT) != 0 ||
            (mode & RadioState.SET_PTY) != 0 ||
            (mode & RadioState.SET_COUNTRY) != 0 ||
            (mode & RadioState.SET_RT_PLUS) != 0
        ) {
            mFrequencyInfo.setRadioState(state);
        }

        if ((mode & RadioState.SET_STEREO) != 0 || isInitial) {
            updateStereoUi(state.isStereo());
        }

        if ((mode & RadioState.SET_RECORDING) != 0 || isInitial) {
            updateRecordingUi(state);
        }
    }

    private void updateStereoUi(final boolean isStereo) {
        if (mViewStereoMode == null || mViewStereoModeCard == null) {
            return;
        }
        final TunerDriver driver = TunerDriverDetector.getTunerDriver(this);
        if (driver.hasKnownCapabilities() && !driver.supportsStereoDetection()) {
            mViewStereoModeCard.setVisibility(View.GONE);
            return;
        }
        mViewStereoModeCard.setVisibility(View.VISIBLE);
        mViewStereoMode.setText(isStereo ? R.string.stereo_mode_label : R.string.mono_mode_label);
        mViewStereoMode.setContentDescription(getString(
                isStereo ? R.string.stereo_mode_active : R.string.mono_mode_active
        ));
        final int bgColor = MaterialColors.getColor(
                mViewStereoModeCard,
                isStereo ? com.google.android.material.R.attr.colorSecondaryContainer : com.google.android.material.R.attr.colorSurfaceContainerHigh,
                ContextCompat.getColor(this, R.color.rfm_md_secondary_container)
        );
        final int strokeColor = MaterialColors.getColor(
                mViewStereoModeCard,
                isStereo ? com.google.android.material.R.attr.colorSecondary : com.google.android.material.R.attr.colorOutlineVariant,
                ContextCompat.getColor(this, R.color.rfm_md_secondary)
        );
        final int fgColor = MaterialColors.getColor(
                mViewStereoMode,
                isStereo ? com.google.android.material.R.attr.colorOnSecondaryContainer : com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(this, R.color.rfm_md_on_secondary_container)
        );
        mViewStereoModeCard.setCardBackgroundColor(bgColor);
        mViewStereoModeCard.setStrokeColor(strokeColor);
        mViewStereoMode.setTextColor(fgColor);
    }

    private void updateAfUi(final int[] af) {
        if (mAfBadge == null || mAfBadgeCard == null) {
            return;
        }
        final int count = af != null ? af.length : 0;
        final boolean hasAf = count > 0;
        mAfBadge.setText(hasAf
                ? String.format(Locale.US, getString(R.string.rds_af_count), count)
                : getString(R.string.rds_af_label)
        );
        final int bgColor = MaterialColors.getColor(
                mAfBadgeCard,
                hasAf ? com.google.android.material.R.attr.colorPrimaryContainer : com.google.android.material.R.attr.colorSurfaceContainerHigh,
                ContextCompat.getColor(this, hasAf ? R.color.rfm_md_primary_container : R.color.rfm_md_secondary_container)
        );
        final int strokeColor = MaterialColors.getColor(
                mAfBadgeCard,
                hasAf ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOutlineVariant,
                ContextCompat.getColor(this, hasAf ? R.color.rfm_md_primary : R.color.rfm_md_secondary_container)
        );
        final int fgColor = MaterialColors.getColor(
                mAfBadge,
                hasAf ? com.google.android.material.R.attr.colorOnPrimaryContainer : com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(this, hasAf ? R.color.rfm_md_on_primary_container : R.color.text_secondary)
        );
        mAfBadgeCard.setCardBackgroundColor(bgColor);
        mAfBadgeCard.setStrokeColor(strokeColor);
        mAfBadge.setTextColor(fgColor);
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
        final int targetBg = MaterialColors.getColor(
                mCtlToggle,
                isPlay ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSurfaceContainerHighest,
                ContextCompat.getColor(this, isPlay ? R.color.rfm_md_primary : R.color.rfm_md_secondary_container)
        );
        final int targetFg = MaterialColors.getColor(
                mCtlToggle,
                isPlay ? com.google.android.material.R.attr.colorOnPrimary : androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(this, isPlay ? R.color.rfm_md_on_primary : R.color.rfm_md_primary)
        );

        if (mLastPowerPlaying == null || mLastPowerPlaying == isPlay || !mCtlToggle.isLaidOut()) {
            mLastPowerPlaying = isPlay;
            mCtlToggle.setBackgroundTintList(ColorStateList.valueOf(targetBg));
            mCtlToggle.setIconTint(ColorStateList.valueOf(targetFg));
            return;
        }

        mLastPowerPlaying = isPlay;
        if (mPowerColorAnimator != null) {
            mPowerColorAnimator.cancel();
        }
        final ColorStateList currentBgTint = mCtlToggle.getBackgroundTintList();
        final ColorStateList currentFgTint = mCtlToggle.getIconTint();
        final int startBg = currentBgTint != null ? currentBgTint.getDefaultColor() : targetBg;
        final int startFg = currentFgTint != null ? currentFgTint.getDefaultColor() : targetFg;
        final ArgbEvaluator evaluator = new ArgbEvaluator();

        mPowerColorAnimator = ValueAnimator.ofFloat(0f, 1f);
        mPowerColorAnimator.setDuration(220L);
        mPowerColorAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mPowerColorAnimator.addUpdateListener(animation -> {
            final float fraction = (float) animation.getAnimatedValue();
            mCtlToggle.setBackgroundTintList(ColorStateList.valueOf((int) evaluator.evaluate(fraction, startBg, targetBg)));
            mCtlToggle.setIconTint(ColorStateList.valueOf((int) evaluator.evaluate(fraction, startFg, targetFg)));
        });
        mPowerColorAnimator.start();
    }

    /**
     * Enables or disables the entire interface
     * @param state True if enabled, false otherwise
     */
    private void setEnabledUi(final boolean state) {
        final @IdRes int[] ids = {
                R.id.frequency_mhz,
                R.id.frequency_ps,
                R.id.frequency_pi,
                R.id.frequency_rt,
                R.id.frequency_pty,
                R.id.frequency_seek,
                R.id.stereo_mono_card,
                R.id.af_badge_card,
                R.id.signal_strength,
                R.id.btn_record,
                R.id.favorite_list
        };

        final float targetAlpha = state ? 1f : .45f;
        for (final int id : ids) {
            final View v = findViewById(id);
            if (v != null) {
                v.setEnabled(state);
                v.animate().cancel();
                if (v.isLaidOut()) {
                    v.animate().alpha(targetAlpha).setDuration(200L).start();
                } else {
                    v.setAlpha(targetAlpha);
                }
            }
        }

        setTransportButtonsVisible(state);
    }

    private void setTransportButtonsVisible(final boolean visible) {
        final View deck = findViewById(R.id.transport_deck);
        final View seekDown = findViewById(R.id.ctl_seek_down);
        final View goDown = findViewById(R.id.ctl_go_down);
        final View goUp = findViewById(R.id.ctl_go_up);
        final View seekUp = findViewById(R.id.ctl_seek_up);
        if (deck == null || seekDown == null || goDown == null || goUp == null || seekUp == null) {
            return;
        }

        final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
        mCtlToggle.bringToFront();

        final View[] buttons = { goDown, seekDown, goUp, seekUp };
        final float toggleCenterX = mCtlToggle.getLeft() + mCtlToggle.getWidth() * 0.5f;

        if (visible) {
            if (seekDown.getVisibility() == View.VISIBLE && seekDown.getAlpha() == 1f) {
                return;
            }
            final long[] delays = { 0L, 45L, 0L, 45L };

            for (final View btn : buttons) {
                btn.animate().cancel();
                btn.setClickable(true);
                btn.setEnabled(true);
                btn.setVisibility(View.VISIBLE);
                if (deck.isLaidOut()) {
                    btn.setAlpha(0f);
                } else {
                    btn.setAlpha(1f);
                    btn.setScaleX(1f);
                    btn.setScaleY(1f);
                    btn.setTranslationX(0f);
                }
            }

            if (deck.isLaidOut()) {
                deck.post(() -> {
                    final float center = mCtlToggle.getLeft() + mCtlToggle.getWidth() * 0.5f;
                    mCtlToggle.bringToFront();
                    for (int i = 0; i < buttons.length; i++) {
                        final View btn = buttons[i];
                        final float startX = center - (btn.getLeft() + btn.getWidth() * 0.5f);
                        btn.setAlpha(0f);
                        btn.setScaleX(0.68f);
                        btn.setScaleY(0.68f);
                        btn.setTranslationX(startX);
                        btn.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setStartDelay(delays[i])
                                .setDuration(280L)
                                .setInterpolator(interpolator)
                                .withEndAction(null)
                                .start();
                    }
                });
            }
        } else {
            if (seekDown.getVisibility() == View.GONE) {
                return;
            }
            final float density = getResources().getDisplayMetrics().density;
            final float[] fallbackOffsets = { 56f * density, 112f * density, -56f * density, -112f * density };

            for (int i = 0; i < buttons.length; i++) {
                final View btn = buttons[i];
                btn.animate().cancel();
                btn.setClickable(false);
                if (deck.isLaidOut() && btn.getVisibility() == View.VISIBLE) {
                    final float endX = btn.getWidth() > 0
                            ? toggleCenterX - (btn.getLeft() + btn.getWidth() * 0.5f)
                            : fallbackOffsets[i];
                    btn.animate()
                            .translationX(endX)
                            .scaleX(0.55f)
                            .scaleY(0.55f)
                            .alpha(0.15f)
                            .setStartDelay(0L)
                            .setDuration(260L)
                            .setInterpolator(interpolator)
                            .withEndAction(() -> {
                                btn.setEnabled(false);
                                btn.setClickable(true);
                                btn.setVisibility(View.GONE);
                                btn.setTranslationX(0f);
                                btn.setScaleX(1f);
                                btn.setScaleY(1f);
                                btn.setAlpha(1f);
                            })
                            .start();
                } else {
                    btn.setEnabled(false);
                    btn.setClickable(true);
                    btn.setVisibility(View.GONE);
                }
            }
        }
    }

    private void updateRecordingUi(final RadioState state) {
        if (mBtnRecord == null || mRecordDuration == null) {
            return;
        }

        final boolean isRecording = state.isRecording();
        final int bgColor = MaterialColors.getColor(
                mBtnRecord,
                isRecording ? com.google.android.material.R.attr.colorErrorContainer : com.google.android.material.R.attr.colorSurfaceContainerHigh,
                ContextCompat.getColor(this, isRecording ? R.color.rfm_md_error_container : R.color.rfm_md_secondary_container)
        );
        final int strokeColor = MaterialColors.getColor(
                mBtnRecord,
                isRecording ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOutlineVariant,
                ContextCompat.getColor(this, isRecording ? R.color.rfm_record : R.color.rfm_md_secondary_container)
        );
        final int fgColor = MaterialColors.getColor(
                mRecordDuration,
                isRecording ? com.google.android.material.R.attr.colorOnErrorContainer : com.google.android.material.R.attr.colorOnSurface,
                ContextCompat.getColor(this, isRecording ? R.color.rfm_md_on_error_container : R.color.text_primary)
        );
        mBtnRecord.setCardBackgroundColor(bgColor);
        mBtnRecord.setStrokeColor(strokeColor);
        mRecordDuration.setTextColor(fgColor);

        if (isRecording) {
            final long durationSec = Math.max(0L, state.getRecordingDuration());
            mRecordDuration.setText(getTimeStringBySeconds(durationSec));
            startRecordPulseAnimation();
            if (!mRecordTickerRunning) {
                mRecordTickerRunning = true;
                mUiHandler.post(mRecordTicker);
            }
        } else {
            mUiHandler.removeCallbacks(mRecordTicker);
            mRecordTickerRunning = false;
            stopRecordPulseAnimation();
            mRecordDuration.setText(R.string.record_badge_idle);
        }
    }

    private void startRecordPulseAnimation() {
        if (mRecordDot == null || (mRecordPulseAnimator != null && mRecordPulseAnimator.isRunning())) {
            return;
        }
        mRecordPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                mRecordDot,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.28f, 1.0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.18f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.18f)
        );
        mRecordPulseAnimator.setDuration(700L);
        mRecordPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mRecordPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mRecordPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        mRecordPulseAnimator.start();
    }

    private void stopRecordPulseAnimation() {
        if (mRecordPulseAnimator != null) {
            mRecordPulseAnimator.cancel();
            mRecordPulseAnimator = null;
        }
        if (mRecordDot != null) {
            mRecordDot.setAlpha(0.3f);
            mRecordDot.setScaleX(1f);
            mRecordDot.setScaleY(1f);
        }
    }
}
