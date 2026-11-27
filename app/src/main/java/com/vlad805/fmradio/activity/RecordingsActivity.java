package com.vlad805.fmradio.activity;

import android.Manifest;
import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.controller.RadioClient;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.recordings.DirectoryListing;
import com.vlad805.fmradio.recordings.RecordingAudioPlayer;
import com.vlad805.fmradio.recordings.RecordingItem;
import com.vlad805.fmradio.recordings.RecordingTunerBinder;
import com.vlad805.fmradio.recordings.RecordingsAdapter;
import com.vlad805.fmradio.recordings.RecordingsRepository;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataEvent;
import com.vlad805.fmradio.view.WaveformView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app browser and player for FM recordings under {@code Music/RFM-Recordings}.
 */
public final class RecordingsActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_RELATIVE_PATH = "initial_relative_path";

    private static final int REQUEST_CODE_STORAGE = 201;
    private static final int MENU_SHARE = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_SORT_TOOLBAR = 10;
    private static final int MENU_SORT_NEWEST = 11;
    private static final int MENU_SORT_OLDEST = 12;
    private static final int MENU_SORT_NAME_ASC = 13;
    private static final int MENU_SORT_NAME_DESC = 14;
    private static final long TICK_INTERVAL_MS = 150L;

    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger mDirectoryGeneration = new AtomicInteger(0);

    private RadioClient mRadioClient;
    private RecordingAudioPlayer mPlayer;
    private RecordingTunerBinder mTunerBinder;
    private RecordingsAdapter mAdapter;
    private BottomSheetBehavior<View> mSheetBehavior;

    private MaterialToolbar mToolbar;
    private RecyclerView mRecyclerView;
    private View mEmptyView;
    private View mMiniPlayerBar;
    private TextView mMiniPath;
    private TextView mMiniTitle;
    private MaterialButton mMiniPlayPause;

    private View mPlayerScrim;
    private View mExpandedContent;
    private TextView mPlayerTitle;
    private TextView mPlayerPath;
    private View mTunerDisplay;
    private View mNoRdsCard;
    private WaveformView mWaveformView;
    private TextView mTimeCurrent;
    private TextView mTimeTotal;
    private MaterialButton mBtnPrev;
    private MaterialButton mBtnPlayPause;
    private MaterialButton mBtnNext;

    private String mCurrentDir = "";
    private int mSortOrder = RecordingsRepository.SORT_NEWEST_FIRST;
    @Nullable
    private RecordingItem mCurrentItem;
    private int mCurrentIndex = -1;
    @Nullable
    private RfmMetadata mCurrentMetadata;
    private long mCurrentDurationMs = 0L;
    private boolean mUserScrubbing = false;
    private boolean mSheetDragging = false;
    @Nullable
    private String mPendingOpenRelativePath;
    @Nullable
    private RecordingItem mPendingDeleteItem;
    @Nullable
    private String mPendingDeleteDir;

    private final ActivityResultLauncher<IntentSenderRequest> mDeleteConsentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            if (mPendingDeleteItem != null) {
                                onRecordingDeleted(mPendingDeleteItem);
                            } else if (mPendingDeleteDir != null) {
                                deleteDirectoryNow(mPendingDeleteDir);
                            }
                        }
                        mPendingDeleteItem = null;
                        mPendingDeleteDir = null;
                    }
            );

    private final Runnable mPlaybackTick = new Runnable() {
        @Override
        public void run() {
            if (mPlayer == null) {
                return;
            }

            if (!mUserScrubbing && !mSheetDragging) {
                final long posMs = mPlayer.getCurrentPositionMs();
                updatePlaybackPositionUi(posMs);
            }

            if (mPlayer.isPlaying()) {
                mMainHandler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    @NonNull
    public static Intent createIntent(@NonNull final Context context, @Nullable final String initialRelativePath) {
        final Intent intent = new Intent(context, RecordingsActivity.class);

        if (initialRelativePath != null && !initialRelativePath.isEmpty()) {
            intent.putExtra(EXTRA_INITIAL_RELATIVE_PATH, initialRelativePath);
        }

        return intent;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);

        mRadioClient = new RadioClient(this, null, null);
        mPlayer = new RecordingAudioPlayer(this);
        mSortOrder = Storage.get(this).getRecordingsSort();

        initViews();
        initPlayerCallbacks();
        initBackHandling();

        final String initialPath = getIntent().getStringExtra(EXTRA_INITIAL_RELATIVE_PATH);
        if (initialPath != null && !initialPath.isEmpty()) {
            mPendingOpenRelativePath = initialPath;
            final int slash = initialPath.lastIndexOf('/');
            mCurrentDir = slash > 0 ? initialPath.substring(0, slash) : "";
        }

        if (hasReadPermission()) {
            loadDirectory(mCurrentDir);
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_CODE_STORAGE
            );
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mRadioClient.connect();
    }

    @Override
    protected void onStop() {
        mRadioClient.disconnect();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mMainHandler.removeCallbacks(mPlaybackTick);
        mPlayer.release();
        mTunerBinder.release();
        mIoExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            @NonNull final String[] permissions,
            @NonNull final int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(mCurrentDir);
            } else {
                Toast.makeText(this, R.string.recordings_permission_denied, Toast.LENGTH_LONG).show();
                mEmptyView.setVisibility(View.VISIBLE);
            }
        }
    }

    private void initViews() {
        mToolbar = findViewById(R.id.recordings_toolbar);
        mToolbar.setNavigationOnClickListener(v -> navigateBack());
        final Menu toolbarMenu = mToolbar.getMenu();
        toolbarMenu.add(0, MENU_SORT_TOOLBAR, 1, R.string.recordings_sort)
                .setIcon(R.drawable.ic_sort)
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        mToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_SORT_TOOLBAR) {
                final View anchor = mToolbar.findViewById(MENU_SORT_TOOLBAR);
                showSortMenu(anchor != null ? anchor : mToolbar);
                return true;
            }
            return false;
        });

        mRecyclerView = findViewById(R.id.recordings_list);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mEmptyView = findViewById(R.id.recordings_empty);

        mAdapter = new RecordingsAdapter(new RecordingsAdapter.OnItemClickListener() {
            @Override
            public void onDirectoryClick(@NonNull final String dirName) {
                loadDirectory(RecordingsRepository.childRelativeDir(mCurrentDir, dirName));
            }

            @Override
            public void onDirectoryLongClick(@NonNull final View anchor, @NonNull final String dirName) {
                showDirectoryContextMenu(anchor, dirName);
            }

            @Override
            public void onRecordingClick(@NonNull final RecordingItem item, final int recordingIndex) {
                openRecording(item, recordingIndex, true);
            }

            @Override
            public void onRecordingLongClick(@NonNull final View anchor, @NonNull final RecordingItem item) {
                showRecordingContextMenu(anchor, item);
            }
        });
        mRecyclerView.setAdapter(mAdapter);

        final View bottomSheet = findViewById(R.id.player_bottom_sheet);
        mSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        mSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        mMiniPlayerBar = findViewById(R.id.mini_player_bar);
        mMiniPath = findViewById(R.id.mini_player_path);
        mMiniTitle = findViewById(R.id.mini_player_title);
        mMiniPlayPause = findViewById(R.id.mini_player_play_pause);

        mPlayerScrim = findViewById(R.id.player_scrim);
        mPlayerScrim.setOnClickListener(v -> mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED));

        mExpandedContent = findViewById(R.id.expanded_player_content);
        mPlayerTitle = findViewById(R.id.player_title);
        mPlayerPath = findViewById(R.id.player_path);
        mTunerDisplay = findViewById(R.id.player_tuner_display);
        mNoRdsCard = findViewById(R.id.player_no_rds_card);
        mWaveformView = findViewById(R.id.player_waveform);
        mTimeCurrent = findViewById(R.id.player_time_current);
        mTimeTotal = findViewById(R.id.player_time_total);
        mBtnPrev = findViewById(R.id.btn_player_prev);
        mBtnPlayPause = findViewById(R.id.btn_player_play_pause);
        mBtnNext = findViewById(R.id.btn_player_next);

        mTunerBinder = new RecordingTunerBinder(mExpandedContent);

        mMiniPlayerBar.setOnClickListener(v -> mSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        findViewById(R.id.btn_player_collapse).setOnClickListener(
                v -> mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED)
        );
        findViewById(R.id.btn_player_more).setOnClickListener(this::showPlayerOverflowMenu);

        final View.OnClickListener playPauseClick = v -> togglePlayPause();
        mMiniPlayPause.setOnClickListener(playPauseClick);
        mBtnPlayPause.setOnClickListener(playPauseClick);

        mBtnPrev.setOnClickListener(v -> playAdjacent(-1));
        mBtnNext.setOnClickListener(v -> playAdjacent(1));

        mWaveformView.setOnSeekListener(new WaveformView.OnSeekListener() {
            @Override
            public void onSeekStart() {
                mUserScrubbing = true;
            }

            @Override
            public void onSeekChanged(final float fraction) {
                final long targetMs = (long) (fraction * mCurrentDurationMs);
                updatePlaybackPositionUi(targetMs);
            }

            @Override
            public void onSeekEnd(final float fraction) {
                mUserScrubbing = false;
                final long targetMs = (long) (fraction * mCurrentDurationMs);
                mPlayer.seekTo(targetMs);
                updatePlaybackPositionUi(targetMs);
            }
        });

        mSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull final View sheet, final int newState) {
                mSheetDragging = (
                        newState == BottomSheetBehavior.STATE_DRAGGING ||
                        newState == BottomSheetBehavior.STATE_SETTLING
                );

                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    mPlayer.pause();
                    mMainHandler.removeCallbacks(mPlaybackTick);
                    mCurrentItem = null;
                    mCurrentIndex = -1;
                    mAdapter.setActiveRelativePath(null);
                    mPlayerScrim.setVisibility(View.GONE);
                    mPlayerScrim.setAlpha(0f);
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    mMiniPlayerBar.setVisibility(View.INVISIBLE);
                    mExpandedContent.setVisibility(View.VISIBLE);
                    mExpandedContent.setAlpha(1f);
                    mPlayerScrim.setVisibility(View.VISIBLE);
                    mPlayerScrim.setAlpha(1f);
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    mMiniPlayerBar.setVisibility(View.VISIBLE);
                    mMiniPlayerBar.setAlpha(1f);
                    mExpandedContent.setVisibility(View.INVISIBLE);
                    mExpandedContent.setAlpha(0f);
                    mPlayerScrim.setVisibility(View.GONE);
                    mPlayerScrim.setAlpha(0f);
                }
            }

            @Override
            public void onSlide(@NonNull final View sheet, final float slideOffset) {
                final float progress = Math.max(0f, Math.min(1f, slideOffset));
                mMiniPlayerBar.setVisibility(progress < 0.85f ? View.VISIBLE : View.INVISIBLE);
                mMiniPlayerBar.setAlpha(1f - Math.min(1f, progress * 2.5f));
                mExpandedContent.setVisibility(progress > 0.05f ? View.VISIBLE : View.INVISIBLE);
                mExpandedContent.setAlpha(Math.max(0f, (progress - 0.15f) / 0.85f));
                mPlayerScrim.setVisibility(progress > 0.01f ? View.VISIBLE : View.GONE);
                mPlayerScrim.setAlpha(progress);
            }
        });
    }

    private void initPlayerCallbacks() {
        mPlayer.setListener(new RecordingAudioPlayer.Listener() {
            @Override
            public void onPrepared(final long durationMs) {
                mCurrentDurationMs = durationMs;
                mTimeTotal.setText(formatDurationMs(durationMs));
                updateWaveformMarkers();
                updatePlaybackPositionUi(0L);
            }

            @Override
            public void onPlaybackStateChanged(final boolean isPlaying) {
                updatePlayPauseIcons(isPlaying);
                mMainHandler.removeCallbacks(mPlaybackTick);

                if (isPlaying) {
                    mMainHandler.post(mPlaybackTick);
                }
            }

            @Override
            public void onCompleted() {
                mMainHandler.removeCallbacks(mPlaybackTick);
                updatePlayPauseIcons(false);
                updatePlaybackPositionUi(mCurrentDurationMs);
            }

            @Override
            public void onError(@NonNull final String message) {
                mMainHandler.removeCallbacks(mPlaybackTick);
                updatePlayPauseIcons(false);
                Toast.makeText(RecordingsActivity.this, R.string.recordings_playback_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navigateBack();
            }
        });
    }

    private void navigateBack() {
        if (mSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return;
        }

        if (!mCurrentDir.isEmpty()) {
            loadDirectory(RecordingsRepository.parentRelativeDir(mCurrentDir));
            return;
        }

        finish();
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }

        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadDirectory(@NonNull final String relativeDir) {
        mCurrentDir = relativeDir;
        mToolbar.setTitle(R.string.recordings_title);
        mToolbar.setSubtitle(relativeDir.isEmpty() ? null : relativeDir);

        final int generation = mDirectoryGeneration.incrementAndGet();

        mIoExecutor.execute(() -> {
            final DirectoryListing listing = RecordingsRepository.listDirectory(this, relativeDir, mSortOrder);

            mMainHandler.post(() -> {
                if (generation != mDirectoryGeneration.get()) {
                    return;
                }

                mAdapter.setListing(listing);
                mEmptyView.setVisibility(listing.isEmpty() ? View.VISIBLE : View.GONE);
                updateAdjacentButtons();

                if (mPendingOpenRelativePath != null) {
                    final String target = mPendingOpenRelativePath;
                    mPendingOpenRelativePath = null;
                    final List<RecordingItem> recs = listing.getRecordings();

                    for (int i = 0; i < recs.size(); i++) {
                        if (target.equals(recs.get(i).getRelativePath())) {
                            openRecording(recs.get(i), i, false);
                            break;
                        }
                    }
                }
            });

            // Lazily probe RFM1 metadata presence for each recording to show the RDS badge.
            final List<RecordingItem> recordings = listing.getRecordings();
            for (int i = 0; i < recordings.size(); i++) {
                if (generation != mDirectoryGeneration.get()) {
                    return;
                }

                final RecordingItem item = recordings.get(i);
                boolean hasMeta = false;

                try {
                    hasMeta = RecordingsRepository.readMetadata(this, item) != null;
                } catch (final IOException ignored) {
                }

                if (hasMeta) {
                    final int idx = i;
                    final RecordingItem updated = item.withProbedInfo(item.getDurationMillis(), true);
                    mMainHandler.post(() -> {
                        if (generation == mDirectoryGeneration.get()) {
                            mAdapter.updateRecording(idx, updated);
                        }
                    });
                }
            }
        });
    }

    private void openRecording(@NonNull final RecordingItem item, final int index, final boolean autoPlay) {
        if (autoPlay) {
            stopLiveFmIfActive();
        }

        mCurrentItem = item;
        mCurrentIndex = index;
        mCurrentMetadata = null;
        mCurrentDurationMs = item.getDurationMillis();

        final String dirLabel = item.getRelativeDir().isEmpty()
                ? getString(R.string.recordings_root_label)
                : item.getRelativeDir();

        mMiniPath.setText(dirLabel);
        mMiniTitle.setText(item.getDisplayName());
        mPlayerPath.setText(dirLabel);
        mPlayerTitle.setText(item.getDisplayName());
        mTimeCurrent.setText(formatDurationMs(0L));
        mTimeTotal.setText(formatDurationMs(mCurrentDurationMs));
        mWaveformView.setPeaks(null);
        mWaveformView.setMarkers(null);
        mWaveformView.setProgress(0f);
        mTunerBinder.clear();

        mAdapter.setActiveRelativePath(item.getRelativePath());
        updateAdjacentButtons();

        if (mSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        mPlayer.prepare(item, autoPlay);

        final RecordingItem requested = item;
        mIoExecutor.execute(() -> {
            RfmMetadata metadata = null;

            try {
                metadata = RecordingsRepository.readMetadata(this, requested);
            } catch (final IOException ignored) {
            }

            final RfmMetadata loaded = metadata;
            mMainHandler.post(() -> {
                if (mCurrentItem == null || !mCurrentItem.getRelativePath().equals(requested.getRelativePath())) {
                    return;
                }

                mCurrentMetadata = loaded;
                applyLoadedMetadata(loaded);
            });
        });
    }

    private void applyLoadedMetadata(@Nullable final RfmMetadata metadata) {
        if (metadata == null) {
            mTunerDisplay.setVisibility(View.GONE);
            mNoRdsCard.setVisibility(View.VISIBLE);
            mWaveformView.setPeaks(null);
            mWaveformView.setMarkers(null);
            return;
        }

        mNoRdsCard.setVisibility(View.GONE);
        mTunerDisplay.setVisibility(View.VISIBLE);
        mWaveformView.setPeaks(metadata.getWaveformPeaks());
        updateWaveformMarkers();
        updatePlaybackPositionUi(mPlayer.getCurrentPositionMs());
    }

    private void updateWaveformMarkers() {
        if (mCurrentMetadata == null || mCurrentDurationMs <= 0L) {
            mWaveformView.setMarkers(null);
            return;
        }

        final List<RfmMetadataEvent> events = mCurrentMetadata.getEvents();
        final List<Float> markers = new ArrayList<>();
        int lastFreq = -1;

        for (final RfmMetadataEvent event : events) {
            final int freq = event.getSnapshot().getFrequencyKhz();
            if (lastFreq > 0 && freq > 0 && freq != lastFreq && event.getTimeMs() > 0L) {
                markers.add((float) event.getTimeMs() / (float) mCurrentDurationMs);
            }
            if (freq > 0) {
                lastFreq = freq;
            }
        }

        final float[] array = new float[markers.size()];
        for (int i = 0; i < markers.size(); i++) {
            array[i] = markers.get(i);
        }
        mWaveformView.setMarkers(array);
    }

    private void updatePlaybackPositionUi(final long positionMs) {
        mTimeCurrent.setText(formatDurationMs(positionMs));

        if (mCurrentDurationMs > 0L) {
            mWaveformView.setProgress((float) positionMs / (float) mCurrentDurationMs);
        }

        if (mCurrentMetadata != null) {
            mTunerBinder.bind(mCurrentMetadata.getEventAt(positionMs));
        }
    }

    private void togglePlayPause() {
        if (mCurrentItem == null) {
            return;
        }

        if (mPlayer.isPlaying()) {
            mPlayer.pause();
        } else {
            stopLiveFmIfActive();
            mPlayer.play();
        }
    }

    private void playAdjacent(final int delta) {
        final List<RecordingItem> recs = mAdapter.getRecordings();
        final int nextIdx = mCurrentIndex + delta;

        if (nextIdx < 0 || nextIdx >= recs.size()) {
            return;
        }

        openRecording(recs.get(nextIdx), nextIdx, true);
    }

    private void updateAdjacentButtons() {
        final int size = mAdapter.getRecordings().size();
        mBtnPrev.setEnabled(mCurrentIndex > 0 && mCurrentIndex < size);
        mBtnNext.setEnabled(mCurrentIndex >= 0 && mCurrentIndex < size - 1);
    }

    private void updatePlayPauseIcons(final boolean isPlaying) {
        final int iconRes = isPlaying ? R.drawable.ic_rfm_pause : R.drawable.ic_rfm_play;
        mMiniPlayPause.setIconResource(iconRes);
        mBtnPlayPause.setIconResource(iconRes);
    }

    private void stopLiveFmIfActive() {
        final TunerStatus status = mRadioClient.getState().getStatus();

        if (
                status == TunerStatus.ENABLED ||
                status == TunerStatus.LAUNCHED ||
                status == TunerStatus.INSTALLING ||
                status == TunerStatus.INSTALLED
        ) {
            mRadioClient.disable();
        }
    }

    private void showSortMenu(@NonNull final View anchor) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        final Menu menu = popup.getMenu();
        menu.add(1, MENU_SORT_NEWEST, 1, R.string.recordings_sort_newest);
        menu.add(1, MENU_SORT_OLDEST, 2, R.string.recordings_sort_oldest);
        menu.add(1, MENU_SORT_NAME_ASC, 3, R.string.recordings_sort_name_asc);
        menu.add(1, MENU_SORT_NAME_DESC, 4, R.string.recordings_sort_name_desc);
        menu.setGroupCheckable(1, true, true);

        final int activeId;
        switch (mSortOrder) {
            case RecordingsRepository.SORT_OLDEST_FIRST: {
                activeId = MENU_SORT_OLDEST;
                break;
            }

            case RecordingsRepository.SORT_NAME_ASC: {
                activeId = MENU_SORT_NAME_ASC;
                break;
            }

            case RecordingsRepository.SORT_NAME_DESC: {
                activeId = MENU_SORT_NAME_DESC;
                break;
            }

            case RecordingsRepository.SORT_NEWEST_FIRST:
            default: {
                activeId = MENU_SORT_NEWEST;
                break;
            }
        }
        menu.findItem(activeId).setChecked(true);

        popup.setOnMenuItemClickListener(item -> {
            final int id = item.getItemId();
            final int nextSort;

            if (id == MENU_SORT_OLDEST) {
                nextSort = RecordingsRepository.SORT_OLDEST_FIRST;
            } else if (id == MENU_SORT_NAME_ASC) {
                nextSort = RecordingsRepository.SORT_NAME_ASC;
            } else if (id == MENU_SORT_NAME_DESC) {
                nextSort = RecordingsRepository.SORT_NAME_DESC;
            } else {
                nextSort = RecordingsRepository.SORT_NEWEST_FIRST;
            }

            if (nextSort != mSortOrder) {
                mSortOrder = nextSort;
                Storage.get(this).setRecordingsSort(nextSort);
                loadDirectory(mCurrentDir);
            }
            return true;
        });

        popup.show();
    }

    private void showDirectoryContextMenu(@NonNull final View anchor, @NonNull final String dirName) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, MENU_DELETE, 1, R.string.record_delete_action);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_DELETE) {
                confirmDeleteDirectory(dirName);
                return true;
            }
            return false;
        });

        popup.show();
    }

    private void showRecordingContextMenu(@NonNull final View anchor, @NonNull final RecordingItem target) {
        final PopupMenu popup = new PopupMenu(this, anchor);
        final Menu menu = popup.getMenu();
        menu.add(0, MENU_SHARE, 1, R.string.recordings_action_share);
        menu.add(0, MENU_DELETE, 2, R.string.record_delete_action);

        popup.setOnMenuItemClickListener(menuItem -> {
            final int id = menuItem.getItemId();

            if (id == MENU_SHARE) {
                shareRecording(target);
                return true;
            }

            if (id == MENU_DELETE) {
                confirmDeleteRecording(target);
                return true;
            }

            return false;
        });

        popup.show();
    }

    private void confirmDeleteDirectory(@NonNull final String dirName) {
        final String fullRelDir = RecordingsRepository.childRelativeDir(mCurrentDir, dirName);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recordings_delete_dir_title)
                .setMessage(getString(R.string.recordings_delete_dir_confirm, dirName))
                .setPositiveButton(R.string.record_delete_action, (d, which) -> deleteDirectoryNow(fullRelDir))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteDirectoryNow(@NonNull final String fullRelDir) {
        if (mCurrentItem != null) {
            final String curDir = mCurrentItem.getRelativeDir();
            if (curDir.equals(fullRelDir) || curDir.startsWith(fullRelDir + "/")) {
                mPlayer.release();
                mCurrentItem = null;
                mCurrentIndex = -1;
                mCurrentMetadata = null;
                mSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            }
        }

        mIoExecutor.execute(() -> {
            try {
                RecordingsRepository.deleteDirectory(this, fullRelDir);
                mMainHandler.post(() -> {
                    Toast.makeText(this, R.string.recordings_delete_dir_success, Toast.LENGTH_SHORT).show();
                    loadDirectory(mCurrentDir);
                });
            } catch (final SecurityException securityError) {
                if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        securityError instanceof RecoverableSecurityException
                ) {
                    final RecoverableSecurityException rse = (RecoverableSecurityException) securityError;
                    mMainHandler.post(() -> {
                        mPendingDeleteDir = fullRelDir;
                        final IntentSenderRequest request = new IntentSenderRequest.Builder(
                                rse.getUserAction().getActionIntent().getIntentSender()
                        ).build();
                        mDeleteConsentLauncher.launch(request);
                    });
                } else {
                    mMainHandler.post(() -> Toast.makeText(
                            this,
                            R.string.recordings_delete_dir_failed,
                            Toast.LENGTH_LONG
                    ).show());
                }
            } catch (final IOException error) {
                mMainHandler.post(() -> Toast.makeText(
                        this,
                        R.string.recordings_delete_dir_failed,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private void showPlayerOverflowMenu(@NonNull final View anchor) {
        if (mCurrentItem == null) {
            return;
        }

        showRecordingContextMenu(anchor, mCurrentItem);
    }

    private void shareRecording(@NonNull final RecordingItem item) {
        try {
            final Intent shareIntent = RecordingsRepository.createShareIntent(
                    this,
                    item,
                    getString(R.string.recordings_action_share)
            );
            startActivity(shareIntent);
        } catch (final RuntimeException error) {
            Toast.makeText(this, R.string.recordings_share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteRecording(@NonNull final RecordingItem target) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(getString(R.string.record_delete_confirm, target.getDisplayName()))
                .setPositiveButton(R.string.record_delete_action, (d, which) -> deleteRecording(target))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteRecording(@NonNull final RecordingItem target) {
        mPlayer.pause();

        try {
            if (RecordingsRepository.delete(this, target)) {
                onRecordingDeleted(target);
            } else {
                Toast.makeText(this, R.string.record_delete_failed, Toast.LENGTH_LONG).show();
            }
        } catch (final SecurityException securityError) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && securityError instanceof RecoverableSecurityException) {
                mPendingDeleteItem = target;
                final RecoverableSecurityException rse = (RecoverableSecurityException) securityError;
                final IntentSenderRequest request = new IntentSenderRequest.Builder(
                        rse.getUserAction().getActionIntent().getIntentSender()
                ).build();
                mDeleteConsentLauncher.launch(request);
            } else {
                Toast.makeText(this, R.string.record_delete_failed, Toast.LENGTH_LONG).show();
            }
        } catch (final IOException error) {
            Toast.makeText(this, R.string.record_delete_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void onRecordingDeleted(@NonNull final RecordingItem deleted) {
        Toast.makeText(this, R.string.record_delete_success, Toast.LENGTH_SHORT).show();

        if (mCurrentItem != null && mCurrentItem.getRelativePath().equals(deleted.getRelativePath())) {
            mPlayer.release();
            mCurrentItem = null;
            mCurrentIndex = -1;
            mCurrentMetadata = null;
            mSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        }

        loadDirectory(mCurrentDir);
    }

    @NonNull
    private static String formatDurationMs(final long ms) {
        final int totalSeconds = (int) Math.max(0L, ms / 1000L);
        return Utils.getTimeStringBySeconds(totalSeconds);
    }
}
