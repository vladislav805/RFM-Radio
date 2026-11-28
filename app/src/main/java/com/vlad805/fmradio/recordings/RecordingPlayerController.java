package com.vlad805.fmradio.recordings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.recordings.trim.RecordingTrimmer;
import com.vlad805.fmradio.service.recording.RfmMetadata;
import com.vlad805.fmradio.service.recording.RfmMetadataEvent;
import com.vlad805.fmradio.view.WaveformView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the persistent mini-player / expandable player drawer bottom sheet,
 * audio playback, live RFM1 RDS replay, waveform seeking, and interactive trim UI.
 */
public final class RecordingPlayerController {
    private static final int MENU_SHARE = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_TRIM = 3;
    private static final int MENU_RENAME = 4;
    private static final long TICK_INTERVAL_MS = 150L;
    private static final long TRIM_STEP_MS = 200L;

    public interface Host {
        void onPlaybackStarting();

        void onNavigateAdjacent(int delta);

        void onActiveItemChanged(@Nullable String relativePath);

        void onShareRequested(@NonNull RecordingItem item);

        void onRenameRequested(@NonNull RecordingItem item);

        void onDeleteRequested(@NonNull RecordingItem item);

        void onTrimRequested(
                @NonNull RecordingItem item,
                long startMs,
                long endMs,
                @NonNull RecordingTrimmer.SaveMode mode
        );
    }

    private final Context mContext;
    private final Host mHost;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final RecordingAudioPlayer mPlayer;
    private final RecordingTunerBinder mTunerBinder;
    private final BottomSheetBehavior<View> mSheetBehavior;

    private final View mPlayerScrim;
    private final View mMiniPlayerBar;
    private final TextView mMiniPath;
    private final TextView mMiniTitle;
    private final MaterialButton mMiniPlayPause;

    private final View mExpandedContent;
    private final View mBtnPlayerMore;
    private final TextView mPlayerTitle;
    private final TextView mPlayerPath;
    private final View mTunerDisplay;
    private final View mNoRdsCard;
    private final WaveformView mWaveformView;
    private final TextView mTimeCurrent;
    private final TextView mTimeTotal;
    private final View mTransportControls;
    private final MaterialButton mBtnPrev;
    private final MaterialButton mBtnPlayPause;
    private final MaterialButton mBtnNext;
    private final View mTrimControls;
    private final TextView mTrimStartText;
    private final TextView mTrimEndText;
    private final TextView mTrimDurationText;
    private final MaterialButton mBtnTrimPreview;

    @Nullable
    private RecordingItem mCurrentItem;
    @Nullable
    private RfmMetadata mCurrentMetadata;
    private long mCurrentDurationMs = 0L;
    private boolean mUserScrubbing = false;
    private boolean mSheetDragging = false;
    private boolean mTrimMode = false;
    private long mTrimStartMs = 0L;
    private long mTrimEndMs = 0L;

    private final Runnable mPlaybackTick = new Runnable() {
        @Override
        public void run() {
            if (!mUserScrubbing && !mSheetDragging) {
                final long posMs = mPlayer.getCurrentPositionMs();

                if (mTrimMode) {
                    if (posMs < mTrimStartMs) {
                        mPlayer.seekTo(mTrimStartMs);
                        updatePlaybackPositionUi(mTrimStartMs);
                        return;
                    }

                    if (posMs >= mTrimEndMs) {
                        mPlayer.pause();
                        mPlayer.seekTo(mTrimStartMs);
                        updatePlaybackPositionUi(mTrimStartMs);
                        return;
                    }
                }

                updatePlaybackPositionUi(posMs);
            }

            if (mPlayer.isPlaying()) {
                mMainHandler.postDelayed(this, TICK_INTERVAL_MS);
            }
        }
    };

    public RecordingPlayerController(
            @NonNull final Context context,
            @NonNull final View rootView,
            @NonNull final Host host
    ) {
        mContext = context;
        mHost = host;
        mPlayer = new RecordingAudioPlayer(context);

        final View bottomSheet = rootView.findViewById(R.id.player_bottom_sheet);
        mSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        mSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);

        mPlayerScrim = rootView.findViewById(R.id.player_scrim);
        mMiniPlayerBar = rootView.findViewById(R.id.mini_player_bar);
        mMiniPath = rootView.findViewById(R.id.mini_player_path);
        mMiniTitle = rootView.findViewById(R.id.mini_player_title);
        mMiniPlayPause = rootView.findViewById(R.id.mini_player_play_pause);

        mExpandedContent = rootView.findViewById(R.id.expanded_player_content);
        mBtnPlayerMore = rootView.findViewById(R.id.btn_player_more);
        mPlayerTitle = rootView.findViewById(R.id.player_title);
        mPlayerPath = rootView.findViewById(R.id.player_path);
        mTunerDisplay = rootView.findViewById(R.id.player_tuner_display);
        mNoRdsCard = rootView.findViewById(R.id.player_no_rds_card);
        mWaveformView = rootView.findViewById(R.id.player_waveform);
        mTimeCurrent = rootView.findViewById(R.id.player_time_current);
        mTimeTotal = rootView.findViewById(R.id.player_time_total);
        mTransportControls = rootView.findViewById(R.id.player_transport_controls);
        mBtnPrev = rootView.findViewById(R.id.btn_player_prev);
        mBtnPlayPause = rootView.findViewById(R.id.btn_player_play_pause);
        mBtnNext = rootView.findViewById(R.id.btn_player_next);

        mTrimControls = rootView.findViewById(R.id.trim_controls_container);
        mTrimStartText = rootView.findViewById(R.id.trim_start_time);
        mTrimEndText = rootView.findViewById(R.id.trim_end_time);
        mTrimDurationText = rootView.findViewById(R.id.trim_duration_text);
        mBtnTrimPreview = rootView.findViewById(R.id.btn_trim_preview);

        mTunerBinder = new RecordingTunerBinder(mExpandedContent);

        bindViewListeners(rootView);
        bindPlayerCallbacks();
    }

    public void open(@NonNull final RecordingItem item, final boolean autoPlay) {
        if (mTrimMode) {
            exitTrimMode();
        }

        if (autoPlay) {
            mHost.onPlaybackStarting();
        }

        mCurrentItem = item;
        mCurrentMetadata = null;
        mCurrentDurationMs = item.getDurationMillis();

        final String dirLabel = item.getRelativeDir().isEmpty()
                ? mContext.getString(R.string.recordings_root_label)
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

        mHost.onActiveItemChanged(item.getRelativePath());

        if (mSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }

        mPlayer.prepare(item, autoPlay);
    }

    public void applyMetadata(
            @NonNull final String relativePath,
            @Nullable final RfmMetadata metadata
    ) {
        if (mCurrentItem == null || !mCurrentItem.getRelativePath().equals(relativePath)) {
            return;
        }

        mCurrentMetadata = metadata;

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

    public void setAdjacentEnabled(final boolean hasPrev, final boolean hasNext) {
        mBtnPrev.setEnabled(hasPrev);
        mBtnNext.setEnabled(hasNext);
    }

    public void pause() {
        mPlayer.pause();
    }

    public void releasePlayer() {
        mMainHandler.removeCallbacks(mPlaybackTick);
        mPlayer.release();
    }

    public void onItemDeleted(@NonNull final String relativePath) {
        if (mCurrentItem != null && mCurrentItem.getRelativePath().equals(relativePath)) {
            closePlayer();
        }
    }

    public void onItemRenamed(
            @NonNull final String oldRelativePath,
            @NonNull final RecordingItem renamed
    ) {
        if (mCurrentItem == null || !mCurrentItem.getRelativePath().equals(oldRelativePath)) {
            return;
        }

        mCurrentItem = renamed;
        mMiniTitle.setText(renamed.getDisplayName());
        mPlayerTitle.setText(renamed.getDisplayName());
        mHost.onActiveItemChanged(renamed.getRelativePath());
    }

    public void closeIfInDirectory(@NonNull final String fullRelDir) {
        if (mCurrentItem == null) {
            return;
        }

        final String curDir = mCurrentItem.getRelativeDir();
        if (curDir.equals(fullRelDir) || curDir.startsWith(fullRelDir + "/")) {
            closePlayer();
        }
    }

    public void onTrimCompleted() {
        exitTrimMode();
    }

    public void onTrimFailed(@NonNull final RecordingItem source) {
        if (mCurrentItem != null && mCurrentItem.getRelativePath().equals(source.getRelativePath())) {
            mPlayer.prepare(source, false);
        }
    }

    public boolean handleBackPressed() {
        if (mTrimMode) {
            requestExitTrimMode(null);
            return true;
        }

        if (mSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return true;
        }

        return false;
    }

    public void release() {
        mMainHandler.removeCallbacks(mPlaybackTick);
        mPlayer.release();
        mTunerBinder.release();
    }

    private void closePlayer() {
        if (mTrimMode) {
            exitTrimMode();
        }

        mMainHandler.removeCallbacks(mPlaybackTick);
        mPlayer.release();
        mCurrentItem = null;
        mCurrentMetadata = null;
        mSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        mHost.onActiveItemChanged(null);
    }

    private void bindViewListeners(@NonNull final View rootView) {
        mPlayerScrim.setOnClickListener(v -> requestCollapseDrawer());
        mMiniPlayerBar.setOnClickListener(v -> mSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        rootView.findViewById(R.id.btn_player_collapse).setOnClickListener(v -> requestCollapseDrawer());
        mBtnPlayerMore.setOnClickListener(this::showPlayerOverflowMenu);

        final View.OnClickListener playPauseClick = v -> togglePlayPause();
        mMiniPlayPause.setOnClickListener(playPauseClick);
        mBtnPlayPause.setOnClickListener(playPauseClick);

        mBtnPrev.setOnClickListener(v -> mHost.onNavigateAdjacent(-1));
        mBtnNext.setOnClickListener(v -> mHost.onNavigateAdjacent(1));

        rootView.findViewById(R.id.btn_trim_start_minus).setOnClickListener(v -> adjustTrimStart(-TRIM_STEP_MS));
        rootView.findViewById(R.id.btn_trim_start_plus).setOnClickListener(v -> adjustTrimStart(TRIM_STEP_MS));
        rootView.findViewById(R.id.btn_trim_end_minus).setOnClickListener(v -> adjustTrimEnd(-TRIM_STEP_MS));
        rootView.findViewById(R.id.btn_trim_end_plus).setOnClickListener(v -> adjustTrimEnd(TRIM_STEP_MS));
        rootView.findViewById(R.id.btn_trim_cancel).setOnClickListener(v -> requestExitTrimMode(null));
        mBtnTrimPreview.setOnClickListener(v -> toggleTrimPreview());
        rootView.findViewById(R.id.btn_trim_save).setOnClickListener(v -> showTrimSaveDialog());

        mWaveformView.setOnTrimRangeChangeListener((startFraction, endFraction, fromUser) -> {
            if (!fromUser || mCurrentDurationMs <= 0L) {
                return;
            }

            mTrimStartMs = Math.round(startFraction * mCurrentDurationMs);
            mTrimEndMs = Math.round(endFraction * mCurrentDurationMs);
            updateTrimLabels();

            if (mPlayer.isPlaying()) {
                final long cur = mPlayer.getCurrentPositionMs();
                if (cur < mTrimStartMs || cur >= mTrimEndMs) {
                    mPlayer.seekTo(mTrimStartMs);
                    updatePlaybackPositionUi(mTrimStartMs);
                }
            } else {
                final long previewPos = Math.round(mWaveformView.getProgress() * mCurrentDurationMs);
                updatePlaybackPositionUi(previewPos);
                mPlayer.seekTo(previewPos);
            }
        });

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

                if (
                        mTrimMode &&
                        (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN)
                ) {
                    if (hasTrimChanges()) {
                        mSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                        requestExitTrimMode(() -> mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED));
                        return;
                    }

                    exitTrimMode();
                }

                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    mPlayer.pause();
                    mMainHandler.removeCallbacks(mPlaybackTick);
                    mCurrentItem = null;
                    mCurrentMetadata = null;
                    mHost.onActiveItemChanged(null);
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

    private void bindPlayerCallbacks() {
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
                Toast.makeText(mContext, R.string.recordings_playback_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateWaveformMarkers() {
        if (mCurrentMetadata == null || mCurrentDurationMs <= 0L) {
            mWaveformView.setMarkers(null);
            return;
        }

        final List<RfmMetadataEvent> events = mCurrentMetadata.getEvents();
        final List<WaveformView.Marker> markers = new ArrayList<>();
        int lastFreq = -1;

        for (final RfmMetadataEvent event : events) {
            final int freq = event.getSnapshot().getFrequencyKhz();
            if (lastFreq > 0 && freq > 0 && freq != lastFreq && event.getTimeMs() > 0L) {
                final float fraction = (float) event.getTimeMs() / (float) mCurrentDurationMs;
                markers.add(new WaveformView.Marker(fraction, formatMarkerFrequency(freq)));
            }

            if (freq > 0) {
                lastFreq = freq;
            }
        }

        mWaveformView.setMarkers(markers);
    }

    @NonNull
    private static String formatMarkerFrequency(final int frequencyKhz) {
        if (frequencyKhz % 100 == 0) {
            return String.format(Locale.US, "%.1f", frequencyKhz / 1000f);
        }

        return String.format(Locale.US, "%.2f", frequencyKhz / 1000f);
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
            mHost.onPlaybackStarting();
            mPlayer.play();
        }
    }

    private void updatePlayPauseIcons(final boolean isPlaying) {
        final int iconRes = isPlaying ? R.drawable.ic_rfm_pause : R.drawable.ic_rfm_play;
        mMiniPlayPause.setIconResource(iconRes);
        mBtnPlayPause.setIconResource(iconRes);
        mBtnTrimPreview.setText(isPlaying ? R.string.recording_trim_pause : R.string.recording_trim_preview);
    }

    private void showPlayerOverflowMenu(@NonNull final View anchor) {
        if (mCurrentItem == null) {
            return;
        }

        final RecordingItem target = mCurrentItem;
        final PopupMenu popup = new PopupMenu(mContext, anchor);
        final Menu menu = popup.getMenu();
        menu.add(0, MENU_SHARE, 1, R.string.recordings_action_share);
        menu.add(0, MENU_RENAME, 2, R.string.recordings_action_rename);
        menu.add(0, MENU_TRIM, 3, R.string.recording_action_trim);
        menu.add(0, MENU_DELETE, 4, R.string.record_delete_action);

        popup.setOnMenuItemClickListener(menuItem -> {
            final int id = menuItem.getItemId();

            if (id == MENU_SHARE) {
                mHost.onShareRequested(target);
                return true;
            }

            if (id == MENU_RENAME) {
                mHost.onRenameRequested(target);
                return true;
            }

            if (id == MENU_TRIM) {
                enterTrimMode();
                return true;
            }

            if (id == MENU_DELETE) {
                mHost.onDeleteRequested(target);
                return true;
            }

            return false;
        });

        popup.show();
    }

    private void enterTrimMode() {
        if (mCurrentItem == null || mCurrentDurationMs < RecordingTrimmer.MIN_TRIM_DURATION_MS) {
            Toast.makeText(mContext, R.string.recording_trim_too_short, Toast.LENGTH_SHORT).show();
            return;
        }

        mPlayer.pause();
        mTrimMode = true;
        mTrimStartMs = 0L;
        mTrimEndMs = mCurrentDurationMs;

        final float minSpan = (float) RecordingTrimmer.MIN_TRIM_DURATION_MS / (float) mCurrentDurationMs;
        mWaveformView.setMinTrimSpanFraction(minSpan);
        mWaveformView.setTrimRange(0f, 1f);
        mWaveformView.setTrimMode(true);

        mTransportControls.setVisibility(View.GONE);
        mTrimControls.setVisibility(View.VISIBLE);
        mBtnPlayerMore.setVisibility(View.INVISIBLE);
        updateTrimLabels();
    }

    private void exitTrimMode() {
        if (!mTrimMode) {
            return;
        }

        mPlayer.pause();
        mTrimMode = false;
        mWaveformView.setTrimMode(false);
        mTrimControls.setVisibility(View.GONE);
        mTransportControls.setVisibility(View.VISIBLE);
        mBtnPlayerMore.setVisibility(View.VISIBLE);
    }

    private boolean hasTrimChanges() {
        return mTrimStartMs > 0L || mTrimEndMs < mCurrentDurationMs;
    }

    private void requestCollapseDrawer() {
        if (mTrimMode) {
            requestExitTrimMode(() -> mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED));
        } else {
            mSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    private void requestExitTrimMode(@Nullable final Runnable afterExit) {
        if (!mTrimMode) {
            if (afterExit != null) {
                afterExit.run();
            }

            return;
        }

        if (!hasTrimChanges()) {
            exitTrimMode();
            if (afterExit != null) {
                afterExit.run();
            }

            return;
        }

        new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.recording_trim_discard_title)
                .setMessage(R.string.recording_trim_discard_confirm)
                .setPositiveButton(R.string.recording_trim_discard_action, (d, which) -> {
                    exitTrimMode();
                    if (afterExit != null) {
                        afterExit.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void adjustTrimStart(final long deltaMs) {
        final long maxStart = mTrimEndMs - RecordingTrimmer.MIN_TRIM_DURATION_MS;
        mTrimStartMs = Math.max(0L, Math.min(maxStart, mTrimStartMs + deltaMs));
        syncTrimSelectionUi(mTrimStartMs);
    }

    private void adjustTrimEnd(final long deltaMs) {
        final long minEnd = mTrimStartMs + RecordingTrimmer.MIN_TRIM_DURATION_MS;
        mTrimEndMs = Math.max(minEnd, Math.min(mCurrentDurationMs, mTrimEndMs + deltaMs));
        syncTrimSelectionUi(mTrimEndMs);
    }

    private void syncTrimSelectionUi(final long focusPosMs) {
        if (mCurrentDurationMs > 0L) {
            mWaveformView.setTrimRange(
                    (float) mTrimStartMs / (float) mCurrentDurationMs,
                    (float) mTrimEndMs / (float) mCurrentDurationMs
            );
        }

        updateTrimLabels();

        if (mPlayer.isPlaying()) {
            final long cur = mPlayer.getCurrentPositionMs();
            if (cur < mTrimStartMs || cur >= mTrimEndMs) {
                mPlayer.seekTo(mTrimStartMs);
                updatePlaybackPositionUi(mTrimStartMs);
            }
        } else {
            updatePlaybackPositionUi(focusPosMs);
            mPlayer.seekTo(focusPosMs);
        }
    }

    private void updateTrimLabels() {
        mTrimStartText.setText(formatDurationTenthsMs(mTrimStartMs));
        mTrimEndText.setText(formatDurationTenthsMs(mTrimEndMs));
        mTrimDurationText.setText(formatDurationTenthsMs(Math.max(0L, mTrimEndMs - mTrimStartMs)));
    }

    private void toggleTrimPreview() {
        if (mCurrentItem == null) {
            return;
        }

        if (mPlayer.isPlaying()) {
            mPlayer.pause();
            return;
        }

        mHost.onPlaybackStarting();
        final long cur = mPlayer.getCurrentPositionMs();
        if (cur < mTrimStartMs || cur >= mTrimEndMs - 100L) {
            mPlayer.seekTo(mTrimStartMs);
            updatePlaybackPositionUi(mTrimStartMs);
        }

        mPlayer.play();
    }

    private void showTrimSaveDialog() {
        if (mCurrentItem == null) {
            return;
        }

        mPlayer.pause();
        final CharSequence[] options = new CharSequence[]{
                mContext.getString(R.string.recording_trim_save_copy),
                mContext.getString(R.string.recording_trim_replace)
        };

        new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.recording_action_trim)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        dispatchTrimRequest(RecordingTrimmer.SaveMode.SAVE_AS_COPY);
                    } else {
                        confirmReplaceOriginal();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReplaceOriginal() {
        if (mCurrentItem == null) {
            return;
        }

        new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.recording_trim_replace)
                .setMessage(mContext.getString(R.string.recording_trim_replace_confirm, mCurrentItem.getDisplayName()))
                .setPositiveButton(R.string.recording_trim_save, (d, which) ->
                        dispatchTrimRequest(RecordingTrimmer.SaveMode.REPLACE_ORIGINAL))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void dispatchTrimRequest(@NonNull final RecordingTrimmer.SaveMode mode) {
        if (mCurrentItem == null) {
            return;
        }

        mHost.onTrimRequested(mCurrentItem, mTrimStartMs, mTrimEndMs, mode);
    }

    @NonNull
    private static String formatDurationMs(final long ms) {
        final int totalSeconds = (int) Math.max(0L, ms / 1000L);
        return Utils.getTimeStringBySeconds(totalSeconds);
    }

    @NonNull
    private static String formatDurationTenthsMs(final long ms) {
        final long safe = Math.max(0L, ms);
        final int totalSeconds = (int) (safe / 1000L);
        final int tenths = (int) ((safe % 1000L) / 100L);
        return Utils.getTimeStringBySeconds(totalSeconds) + "." + tenths;
    }
}
