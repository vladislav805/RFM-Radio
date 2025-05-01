package com.vlad805.fmradio.activity;

import android.Manifest; // <-- Add
import androidx.core.content.ContextCompat; // <-- Add this import
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager; // <-- Add
import android.os.Build; // <-- Add
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull; // <-- Add
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat; // <-- Add
import androidx.core.content.ContextCompat; // <-- Add
import androidx.recyclerview.widget.RecyclerView;
// ... other imports remain ...
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
import com.vlad805.fmradio.preferences.BandUtils;
import com.vlad805.fmradio.service.FMService;
import com.vlad805.fmradio.view.FavoritesPanelView;
import com.vlad805.fmradio.view.FrequencyBarView;
import com.vlad805.fmradio.view.RadioUIView;

import static com.vlad805.fmradio.Utils.alert;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

import net.grandcentrix.tray.AppPreferences;

@SuppressLint("NonConstantResourceId")
public class MainActivity extends AppCompatActivity implements View.OnClickListener, RadioStateUpdater.TunerStateListener {
    // --- Permission Request Codes ---
    private static final int REQUEST_CODE_PERMISSIONS_START_RADIO = 101;
    private static final int REQUEST_CODE_PERMISSIONS_RECORD = 102;
    private static final int REQUEST_CODE_PERMISSIONS_PHONE_STATE = 103;
    private static final int REQUEST_CODE_PERMISSIONS_STORAGE_FAVORITES = 104;
    private static final int REQUEST_CODE_FAVORITES_OPENED = 1048; // Keep existing codes
    private static final int REQUEST_CODE_SETTINGS_CHANGED = 1050; // Keep existing codes

    private static final int REQUEST_CODE_PERMISSIONS_NOTIFICATIONS = 105;
    private static final String POST_NOTIFICATIONS_PERMISSION;
    static {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            POST_NOTIFICATIONS_PERMISSION = Manifest.permission.POST_NOTIFICATIONS;
        } else {
            POST_NOTIFICATIONS_PERMISSION = null; // Set to null on older versions
        }
    }
    // Use the dynamically assigned string or an empty array if null
    private static final String[] PERMISSIONS_NOTIFICATIONS =
            (POST_NOTIFICATIONS_PERMISSION != null) ? new String[]{POST_NOTIFICATIONS_PERMISSION} : new String[0];

    // --- Permission Strings ---
    // Group needed to start radio playback (Audio capture)
    private static final String[] PERMISSIONS_START_RADIO = {Manifest.permission.RECORD_AUDIO};
    // Group needed for explicit recording (Audio capture + Storage write)
    private static final String[] PERMISSIONS_RECORD; // Initialized statically below
    // Group needed for call detection
    private static final String[] PERMISSIONS_PHONE_STATE = {Manifest.permission.READ_PHONE_STATE};
    // Group needed for favorites read/write (will need rework for scoped storage)
    private static final String[] PERMISSIONS_STORAGE_FAVORITES; // Initialized statically below

    // Static initializer for permissions that depend on SDK version
    static {
        // POST_NOTIFICATIONS init moved above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSIONS_RECORD = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_MEDIA_AUDIO
            };
            PERMISSIONS_STORAGE_FAVORITES = new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO
            };
        } else {
            PERMISSIONS_RECORD = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            PERMISSIONS_STORAGE_FAVORITES = new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }


    private ProgressDialog mProgress;
    private Toast mToast;
    private RadioUIView mFrequencyInfo;
    private FrequencyBarView mSeek;
    private FavoritesPanelView mFavoriteList;

    private RadioController mRadioController;

    private AppPreferences mPreferences;

    private ImageButton mCtlToggle;

    private TextView mViewRssi;
    private ImageView mViewRssiIcon;
    private ImageView mViewStereoMode;

    private TextView mRecordDuration;

    private RadioState mLastState = new RadioState(); // Initialize to avoid nulls early

    private Menu mMenu;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mToast = Toast.create(this);
        setSupportActionBar(findViewById(R.id.main_toolbar));

        mPreferences = new AppPreferences(this);
        mRadioController = new RadioController(this);

        initUserInterface(); // Initialize UI elements

        // --- Register listeners and set initial state ---
        mRadioController.registerForUpdates(this);
        mRadioController.setPowerMode(PowerMode.NORMAL);
        mRadioController.requestForCurrentState(this); // Request initial state AFTER registering listener

        // --- Permission Checks ---
        checkAndRequestPhoneStatePermission(); // Request for CallReceiver functionality
        checkAndRequestStoragePermissionForFavorites(); // Request for Favorites list loading

        // --- Initial UI state based on auto-startup pref ---
        // NOTE: Auto-startup itself would need RECORD_AUDIO permission, which we can't
        // guarantee is granted at this point. So, we don't call initLogic() here directly.
        // The user will have to press play, which triggers the permission request if needed.
        if (!Storage.getPrefBoolean(this, C.PrefKey.APP_AUTO_STARTUP, C.PrefDefaultValue.APP_AUTO_STARTUP)) {
            // Only disable UI elements if auto-start is off. Play button will handle permission request.
            setEnabledUi(false);
        }
    }

    // --- Helper methods for permission checks ---

    private boolean hasPermissions(String... permissions) {
        if (permissions != null) {
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void checkAndRequestPhoneStatePermission() {
        if (!hasPermissions(PERMISSIONS_PHONE_STATE)) {
            ActivityCompat.requestPermissions(this, PERMISSIONS_PHONE_STATE, REQUEST_CODE_PERMISSIONS_PHONE_STATE);
        }
        // Else: Already granted
    }

    private void checkAndRequestStoragePermissionForFavorites() {
        if (!hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
            ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE_FAVORITES, REQUEST_CODE_PERMISSIONS_STORAGE_FAVORITES);
        } else {
            // Permission granted, load favorites if the view is ready
            if (mFavoriteList != null) {
                mFavoriteList.load();
            }
        }
    }

    // --- UI Initialization ---
    private void initUserInterface() {
        mFrequencyInfo = findViewById(R.id.frequency_info);
        mFavoriteList = findViewById(R.id.favorite_list); // Init the view
        mSeek = findViewById(R.id.frequency_seek);
        mRecordDuration = findViewById(R.id.record_duration);

        final int kHz = Storage.getInstance(this).getInt(C.PrefKey.LAST_FREQUENCY, C.PrefDefaultValue.LAST_FREQUENCY);
        mFrequencyInfo.setFrequency(kHz);
        mSeek.setFrequency(kHz);

        initClickableButtons();
        reloadPreferences(); // Reload prefs that affect UI like frequency bar limits

        // Defer loading favorites until permission check in onCreate
        // if (mFavoriteList != null && hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
        //     mFavoriteList.load();
        // }
    }
    // --- Click Handler with Permission Checks (including Notifications) ---
    @Override
    public void onClick(final View view) {
        switch (view.getId()) {
            // Main play/stop button
            case R.id.ctl_toggle: {
                TunerStatus currentStatus = (mLastState != null) ? mLastState.getStatus() : TunerStatus.IDLE;

                // --- Actions when trying to START the radio ---
                if (currentStatus == TunerStatus.IDLE ||
                        currentStatus == TunerStatus.INSTALLED ||
                        currentStatus == TunerStatus.LAUNCHED ||
                        currentStatus == TunerStatus.FATAL_ERROR ||
                        currentStatus == TunerStatus.LAUNCH_FAILED)
                {
                    // 1. Check Notification Permission (Required BEFORE starting foreground service)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermissions(PERMISSIONS_NOTIFICATIONS)) {
                        ActivityCompat.requestPermissions(this, PERMISSIONS_NOTIFICATIONS, REQUEST_CODE_PERMISSIONS_NOTIFICATIONS);
                        mToast.text("Notification permission needed for status updates.").show();
                        // Don't proceed further until permission is granted (or denied)
                        return;
                    }
                    // 2. Check Record Audio Permission (Required for radio stream)
                    else if (!hasPermissions(PERMISSIONS_START_RADIO)) {
                        ActivityCompat.requestPermissions(this, PERMISSIONS_START_RADIO, REQUEST_CODE_PERMISSIONS_START_RADIO);
                        mToast.text("Audio recording permission needed to play FM radio.").show();
                        // Don't proceed further
                        return;
                    }
                    // 3. All necessary permissions granted - Proceed with starting the radio
                    else {
                        if (currentStatus == TunerStatus.IDLE || currentStatus == TunerStatus.FATAL_ERROR || currentStatus == TunerStatus.LAUNCH_FAILED) {
                            mRadioController.setup(); // This eventually leads to enable()
                        } else { // INSTALLED or LAUNCHED
                            mRadioController.enable();
                        }
                    }
                }
                // --- Action when trying to STOP the radio ---
                else if (currentStatus == TunerStatus.ENABLED) {
                    // Stopping the radio doesn't need special permissions
                    mRadioController.kill();
                }
                // Ignore clicks during transient states
                break;
            }

            // Jump/Seek controls - these assume radio is already enabled (permission granted)
            case R.id.ctl_go_down: {
                if (mLastState != null && mLastState.getStatus() == TunerStatus.ENABLED) mRadioController.jump(Direction.DOWN);
                break;
            }
            case R.id.ctl_go_up: {
                if (mLastState != null && mLastState.getStatus() == TunerStatus.ENABLED) mRadioController.jump(Direction.UP);
                break;
            }
            case R.id.ctl_seek_down: {
                if (mLastState != null && mLastState.getStatus() == TunerStatus.ENABLED) {
                    mRadioController.hwSeek(Direction.DOWN);
                    showProgress(getString(R.string.progress_searching));
                }
                break;
            }
            case R.id.ctl_seek_up: {
                if (mLastState != null && mLastState.getStatus() == TunerStatus.ENABLED) {
                    mRadioController.hwSeek(Direction.UP);
                    showProgress(getString(R.string.progress_searching));
                }
                break;
            }

            case R.id.favorite_button: {
                // Check storage permission before opening activity that requires it
                if (!hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
                    ActivityCompat.requestPermissions(this, PERMISSIONS_STORAGE_FAVORITES, REQUEST_CODE_PERMISSIONS_STORAGE_FAVORITES);
                    mToast.text("Storage permission needed to manage favorites.").show();
                } else {
                    startActivityForResult(new Intent(this, FavoritesListsActivity.class), REQUEST_CODE_FAVORITES_OPENED);
                }
                break;
            }
        }
    }

    // --- Options Menu Handler with Permission Checks (including Notifications) ---
    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.menu_record) {
            // --- Actions when trying to START recording ---
            if (mLastState != null && !mLastState.isRecording()) {
                // 1. Check Notification Permission (for recording status notification)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermissions(PERMISSIONS_NOTIFICATIONS)) {
                    ActivityCompat.requestPermissions(this, PERMISSIONS_NOTIFICATIONS, REQUEST_CODE_PERMISSIONS_NOTIFICATIONS);
                    mToast.text("Notification permission needed for recording status.").show();
                    // Don't proceed until permission result
                    return true; // Handled (by requesting permission)
                }
                // 2. Check Record Audio & Storage Permissions
                else if (!hasPermissions(PERMISSIONS_RECORD)) {
                    ActivityCompat.requestPermissions(this, PERMISSIONS_RECORD, REQUEST_CODE_PERMISSIONS_RECORD);
                    mToast.text("Audio and Storage permissions needed to record.").show();
                    // Don't proceed until permission result
                    return true; // Handled (by requesting permission)
                }
                // 3. All permissions granted - Proceed with starting recording
                else {
                    mRadioController.record(true);
                }
            }
            // --- Actions when trying to STOP recording ---
            else if (mLastState != null && mLastState.isRecording()) {
                // Stopping recording doesn't need special permissions
                mRadioController.record(false);
            }
            return true; // Handled (either started, stopped, or requested permissions)
        }
        // --- Other Menu Items ---
        else if (itemId == R.id.menu_speaker) {
            startService(new Intent(this, FMService.class).setAction(C.Command.SPEAKER_STATE));
            return true; // Handled
        } else if (itemId == R.id.menu_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true; // Handled
        } else if (itemId == R.id.menu_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_CODE_SETTINGS_CHANGED);
            return true; // Handled
        } else if (itemId == android.R.id.home) {
            finish();
            return true; // Handled
        }
        // --- Default ---
        return super.onOptionsItemSelected(item);
    }


    // --- Permission Result Handler (including Notifications) ---
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_CODE_PERMISSIONS_START_RADIO:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Audio granted. Now check Notifications (if needed) before trying to start.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermissions(PERMISSIONS_NOTIFICATIONS)) {
                        // Need notifications too, request them now
                        ActivityCompat.requestPermissions(this, PERMISSIONS_NOTIFICATIONS, REQUEST_CODE_PERMISSIONS_NOTIFICATIONS);
                        mToast.text("Audio permission granted. Notification permission also needed.").show();
                    } else {
                        // All needed permissions (Audio + maybe Notifications) granted.
                        mToast.text("Audio permission granted. Press play again.").show();
                        // Optional: try starting radio automatically here
                        // TunerStatus currentStatus = ...; if (...) { mRadioController.setup(); } else { mRadioController.enable(); }
                    }
                } else {
                    mToast.text("Audio permission denied. Cannot play FM radio.").show();
                    setEnabledUi(false);
                    setPlayingMainPlayButton(false);
                }
                break;

            case REQUEST_CODE_PERMISSIONS_RECORD:
                if (hasPermissions(PERMISSIONS_RECORD)) {
                    // Audio/Storage granted. Check Notifications (if needed) before trying to record.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermissions(PERMISSIONS_NOTIFICATIONS)) {
                        ActivityCompat.requestPermissions(this, PERMISSIONS_NOTIFICATIONS, REQUEST_CODE_PERMISSIONS_NOTIFICATIONS);
                        mToast.text("Recording permissions granted. Notification permission also needed.").show();
                    } else {
                        // All needed permissions (Audio/Storage + maybe Notifications) granted.
                        mToast.text("Recording permissions granted. Press record again.").show();
                        // Optional: try starting record automatically here
                        // mRadioController.record(true);
                    }
                } else {
                    mToast.text("Recording permissions denied. Cannot record audio.").show();
                }
                break;

            case REQUEST_CODE_PERMISSIONS_PHONE_STATE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mToast.text("Phone state permission granted.").show();
                } else {
                    mToast.text("Phone state permission denied. Calls might not pause radio.").show();
                }
                break;

            case REQUEST_CODE_PERMISSIONS_STORAGE_FAVORITES:
                if (hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
                    mToast.text("Storage permission granted.").show();
                    if (mFavoriteList != null) {
                        mFavoriteList.setEnabled(true); // Ensure enabled after grant
                        mFavoriteList.load();
                    }
                } else {
                    mToast.text("Storage permission denied. Cannot load/save favorites.").show();
                    if(mFavoriteList != null) mFavoriteList.setEnabled(false);
                }
                break;

            case REQUEST_CODE_PERMISSIONS_NOTIFICATIONS: // Handle Notification result
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mToast.text("Notification permission granted.").show();
                    // Now the user might need to press Play/Record again, as the initial
                    // action was likely blocked waiting for this permission.
                } else {
                    mToast.text("Notification permission denied. Status updates might not appear.").show();
                    // App can continue, just without notifications.
                }
                break;
        }
    }

    // --- Other methods remain largely the same ---
    // showProgress, hideProgress, onResume, onDestroy, onActivityResult,
    // initClickableButtons, onCreateOptionsMenu, setEnabledToggleButton,
    // reloadPreferences, mOnFrequencyChanged, onStateUpdated,
    // handleChangingState, setPlayingMainPlayButton, setEnabledUi,
    // updateMenuState, setRssi


    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRadioController.unregisterForUpdates();
        // Low power mode logic remains
        if (Storage.getPrefBoolean(this, C.PrefKey.TUNER_POWER_MODE, false)) {
            mRadioController.setPowerMode(PowerMode.LOW);
        }
    }


    // Make sure setEnabledUi handles the favorite list enable state
    private void setEnabledUi(final boolean state) {
        final @IdRes int[] ids = {
                R.id.ctl_go_down,
                R.id.ctl_go_up,
                R.id.ctl_seek_down,
                R.id.ctl_seek_up,
                R.id.frequency_mhz, // Keep frequency display always enabled? Maybe.
                R.id.frequency_ps,
                R.id.frequency_rt,
                R.id.frequency_pty,
                R.id.frequency_seek,
                R.id.rssi_icon,
                R.id.rssi_value,
                R.id.record_duration,
                R.id.stereo_mono,
                R.id.favorite_list, // Include favorite list here
                R.id.favorite_button // Also the button to open favorites
        };

        for (final int id : ids) {
            final View v = findViewById(id);
            if (v != null) { // Add null check
                // Make frequency display always visible, but controls depend on state
                boolean shouldEnable = (id == R.id.frequency_mhz) ? true : state;
                // Ensure favorites list is only enabled if storage permission is granted AND radio is enabled
                if (id == R.id.favorite_list || id == R.id.favorite_button) {
                    shouldEnable = state && hasPermissions(PERMISSIONS_STORAGE_FAVORITES);
                }

                v.setAlpha(shouldEnable ? 1f : .5f);
                v.setEnabled(shouldEnable);
                // Special case for RecyclerView based view
                if (v instanceof RecyclerView) {
                    v.setClickable(shouldEnable);
                    v.setFocusable(shouldEnable);
                }
            }
        }

        updateMenuState(); // Update menu item states too
    }

    // Adjust updateMenuState if necessary, especially for record button
    private void updateMenuState() {
        if (mMenu == null || mLastState == null) { // Add null check for mLastState
            return;
        }

        final boolean isRadioEnabled = TunerStatus.ENABLED.equals(mLastState.getStatus());
        // Check storage/audio permissions for record button enable state
        final boolean canRecord = isRadioEnabled && hasPermissions(PERMISSIONS_RECORD);

        mMenu.findItem(R.id.menu_record).setEnabled(canRecord); // Enable based on permissions too
        mMenu.findItem(R.id.menu_speaker).setEnabled(isRadioEnabled);
        mMenu.findItem(R.id.menu_speaker).setChecked(mLastState.isForceSpeaker());
    }

    // Add other necessary methods like showProgress, hideProgress etc. back in if removed

    // ... (rest of the original MainActivity methods like setRssi, signal arrays, etc.)
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
    protected void onResume() {
        super.onResume();
        reloadPreferences();
        // Refresh UI state based on current permissions and radio state
        if (mLastState != null) {
            setEnabledUi(mLastState.getStatus() == TunerStatus.ENABLED);
            setPlayingMainPlayButton(mLastState.getStatus() == TunerStatus.ENABLED);
        } else {
            setEnabledUi(false);
            setPlayingMainPlayButton(false);
        }
        // Re-check favorite storage permission in case it was granted while paused
        if (mFavoriteList != null && hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
            mFavoriteList.setEnabled(true);
            mFavoriteList.load(); // Reload in case it changed
        } else if (mFavoriteList != null) {
            mFavoriteList.setEnabled(false);
        }

    }


    // ... rest of existing methods like initClickableButtons, reloadPreferences, etc.
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

    private void reloadPreferences() {
        final int regionPref = mPreferences.getInt(C.PrefKey.TUNER_REGION, C.PrefDefaultValue.TUNER_REGION);
        final int spacingPref = mPreferences.getInt(C.PrefKey.TUNER_SPACING, C.PrefDefaultValue.TUNER_SPACING);

        final BandUtils.BandLimit bandLimit = BandUtils.getBandLimit(regionPref);
        final int spacing = BandUtils.getSpacing(spacingPref);

        if (mSeek != null) { // Add null check
            mSeek.setMinMaxValue(bandLimit.lower, bandLimit.upper, spacing);
            mSeek.setOnFrequencyChangeListener(mOnFrequencyChanged);
        }
    }

    private final FrequencyBarView.OnFrequencyChangedListener mOnFrequencyChanged = new FrequencyBarView.OnFrequencyChangedListener() {
        @Override
        public void onChanged(final int kHz) {
            if (mLastState == null || mLastState.getFrequency() == kHz) { // Add null check
                return;
            }
            mRadioController.setFrequency(kHz);
        }
    };


    // Ensure onStateUpdated initializes mLastState if it's null
    @Override
    public void onStateUpdated(final RadioState state, final int mode) {
        if (state == null) return; // Safety check

        mLastState = state; // Update the cached state

        if ((mode & RadioStateUpdater.SET_STATUS) > 0) {
            handleChangingState(state.getStatus());
            // After handling state change, update UI enable state based on new status
            setEnabledUi(state.getStatus() == TunerStatus.ENABLED);
            setPlayingMainPlayButton(state.getStatus() == TunerStatus.ENABLED);
            return; // Return early as state change often implies other things will update
        }
        // Add null checks for UI elements before updating them
        if ((mode & RadioStateUpdater.SET_FREQUENCY) > 0) {
            hideProgress();
            if (mFrequencyInfo != null) mFrequencyInfo.setFrequency(state.getFrequency());
            if (mSeek != null) mSeek.setFrequency(state.getFrequency());
            final String str = getString(R.string.notification_mhz, state.getFrequency() / 1000f, state.getRssi()); // Use correct format string
            mToast.text(str).show();
            if (mFrequencyInfo != null) mFrequencyInfo.setRadioState(state);
            if (mViewRssi != null) mViewRssi.setText("");
        }

        if (
                (mode & RadioStateUpdater.SET_PS) > 0 ||
                        (mode & RadioStateUpdater.SET_PI) > 0 ||
                        (mode & RadioStateUpdater.SET_RT) > 0 ||
                        (mode & RadioStateUpdater.SET_PTY) > 0
        ) {
            if (mFrequencyInfo != null) mFrequencyInfo.setRadioState(state);
        }

        if ((mode & RadioStateUpdater.SET_RSSI) > 0) {
            if (mViewRssi != null && mViewRssiIcon != null) setRssi(state.getRssi());
        }

        if ((mode & RadioStateUpdater.SET_STEREO) > 0) {
            if (mViewStereoMode != null) mViewStereoMode.setImageResource(state.isStereo() ? R.drawable.ic_stereo : R.drawable.ic_mono);
        }

        if ((mode & RadioStateUpdater.SET_RECORDING) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            final boolean isRecording = state.isRecording();
            if (mRecordDuration != null) mRecordDuration.setVisibility(isRecording ? View.VISIBLE : View.GONE);
            if (mMenu != null) {
                MenuItem recordItem = mMenu.findItem(R.id.menu_record);
                if(recordItem != null) {
                    recordItem.setIcon(isRecording ? R.drawable.ic_record_press : R.drawable.ic_record);
                    // Re-check enable state based on recording status and permissions
                    // recordItem.setEnabled(!isRecording && hasPermissions(PERMISSIONS_RECORD));
                }
            }
            if (isRecording && mRecordDuration != null) {
                mRecordDuration.setText(getTimeStringBySeconds(state.getRecordingDuration()));
            }
        }

        if ((mode & RadioStateUpdater.SET_SPEAKER) > 0 || (mode & RadioStateUpdater.SET_INITIAL) > 0) {
            final boolean isSpeaker = state.isForceSpeaker();
            if (mMenu != null) {
                MenuItem speakerItem = mMenu.findItem(R.id.menu_speaker);
                if(speakerItem != null) speakerItem.setChecked(isSpeaker);
            }
        }
        // After any update, ensure general UI enable state matches radio status
        // setEnabledUi(state.getStatus() == TunerStatus.ENABLED);
        // setPlayingMainPlayButton(state.getStatus() == TunerStatus.ENABLED);
    }


    private void handleChangingState(final TunerStatus status) {
        switch (status) {
            case IDLE: // IDLE covers the "off" state
                // case DISABLED: // <-- DELETE THIS LINE
            case FATAL_ERROR: // Treat errors like IDLE for UI reset
            case LAUNCH_FAILED:
                hideProgress();
                setEnabledUi(false);
                setPlayingMainPlayButton(false);
                setEnabledToggleButton(true);
                if (status == TunerStatus.LAUNCH_FAILED) {
                    Utils.alert(
                            this,
                            R.string.main_error_launching_title,
                            R.string.main_error_launching,
                            android.R.string.ok
                    );
                }
                break;

            case INSTALLING:
                showProgress(getString(R.string.progress_installing));
                setEnabledToggleButton(false);
                setEnabledUi(false); // Disable UI during install
                break;

            case INSTALLED:
            case LAUNCHED:
                hideProgress(); // Hide progress, but UI still disabled until enabled state
                setEnabledUi(false);
                setEnabledToggleButton(true); // Button might be enabled to trigger 'enable'
                setPlayingMainPlayButton(false);
                break;

            case LAUNCHING:
                showProgress(getString(R.string.progress_launching));
                setEnabledToggleButton(false);
                setEnabledUi(false);
                break;


            case ENABLING:
                showProgress(getString(R.string.progress_starting));
                setEnabledToggleButton(false);
                setEnabledUi(false);
                break;

            case DISABLING: // Add DISABLING case
                // Potentially show stopping progress? Or just disable UI
                hideProgress(); // Hide any previous progress
                setEnabledUi(false);
                setEnabledToggleButton(false); // Disable toggle while stopping
                break;

            case ENABLED:
                hideProgress();
                setEnabledUi(true); // Enable UI now
                setEnabledToggleButton(true);
                setPlayingMainPlayButton(true); // Show stop icon
                break;
        }
    }

    private void setPlayingMainPlayButton(final boolean isPlay) {
        if (mCtlToggle != null) mCtlToggle.setImageResource(isPlay ? R.drawable.ic_stop : R.drawable.ic_play);
    }

    private void setEnabledToggleButton(final boolean enabled) {
        if (mCtlToggle != null) mCtlToggle.setEnabled(enabled);
    }

    // --- Signal Strength UI ---
    private static final int[] SIGNAL_RES_ID = {
            R.drawable.ic_signal_0, R.drawable.ic_signal_1, R.drawable.ic_signal_2,
            R.drawable.ic_signal_3, R.drawable.ic_signal_4, R.drawable.ic_signal_5,
            R.drawable.ic_signal_6,
    };
    private static final int[] SIGNAL_THRESHOLD = { -110, -98, -86, -74, -62, -50, -10, };

    private void setRssi(final int rssi) {
        if (mViewRssi != null) mViewRssi.setText(String.valueOf(rssi));
        if (mViewRssiIcon != null) {
            int iconRes = R.drawable.ic_signal_0; // Default to lowest
            for (int i = 0; i < SIGNAL_THRESHOLD.length; ++i) {
                if (rssi >= SIGNAL_THRESHOLD[i]) { // Check if >= threshold
                    iconRes = SIGNAL_RES_ID[i];
                } else {
                    break; // Since thresholds are increasing, stop when below
                }
            }
            // Ensure we handle the case where rssi is below the first threshold
            if (rssi < SIGNAL_THRESHOLD[0]) {
                iconRes = SIGNAL_RES_ID[0];
            }
            mViewRssiIcon.setImageResource(iconRes);
        }
    }

    // Need to handle onActivityResult for settings/favorites
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); // Call super
        if (requestCode == REQUEST_CODE_FAVORITES_OPENED) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getBooleanExtra("changed", false)) {
                if (mFavoriteList != null && hasPermissions(PERMISSIONS_STORAGE_FAVORITES)) {
                    mFavoriteList.reload(true); // Reload if changed and permission exists
                }
            }
        } else if (requestCode == REQUEST_CODE_SETTINGS_CHANGED) {
            if (resultCode == RESULT_OK && data != null && data.getBooleanExtra("changed", false)) {
                alert(
                        this,
                        R.string.main_warning_pref_changed_title,
                        R.string.main_warning_pref_changed_content,
                        android.R.string.ok
                );
            }
        }
    }

    // Need onCreateOptionsMenu
    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        mMenu = menu;
        // Update menu state immediately based on initial state
        if (mLastState != null) {
            updateMenuState();
        } else {
            // Default disabled state if state not yet known
            menu.findItem(R.id.menu_record).setEnabled(false);
            menu.findItem(R.id.menu_speaker).setEnabled(false);
            menu.findItem(R.id.menu_speaker).setChecked(false);
        }
        return super.onCreateOptionsMenu(menu);
    }
} // End of MainActivity class