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
import android.text.InputType;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.controller.RadioClient;
import com.vlad805.fmradio.controller.TunerStatus;
import com.vlad805.fmradio.recordings.DirectoryListing;
import com.vlad805.fmradio.recordings.RecordingItem;
import com.vlad805.fmradio.recordings.RecordingPlayerController;
import com.vlad805.fmradio.recordings.RecordingsAdapter;
import com.vlad805.fmradio.recordings.RecordingsRepository;
import com.vlad805.fmradio.recordings.export.RecordingVideoExporter;
import com.vlad805.fmradio.recordings.trim.RecordingTrimmer;
import com.vlad805.fmradio.service.recording.RfmMetadata;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app browser for FM recordings under {@code Music/RFM-Recordings}.
 * Delegates playback, RDS replay, and trim UI to {@link RecordingPlayerController}.
 */
public final class RecordingsActivity extends AppCompatActivity {
    public static final String EXTRA_INITIAL_RELATIVE_PATH = "initial_relative_path";
    public static final String EXTRA_EXPAND_PLAYER = "expand_player";

    private static final int REQUEST_CODE_STORAGE = 201;
    private static final int MENU_SHARE = 1;
    private static final int MENU_DELETE = 2;
    private static final int MENU_RENAME = 3;
    private static final int MENU_SORT_TOOLBAR = 10;
    private static final int MENU_SORT_NEWEST = 11;
    private static final int MENU_SORT_OLDEST = 12;
    private static final int MENU_SORT_NAME_ASC = 13;
    private static final int MENU_SORT_NAME_DESC = 14;

    private static final class PendingRenameRequest {
        final RecordingItem item;
        final String newBaseName;

        PendingRenameRequest(@NonNull final RecordingItem item, @NonNull final String newBaseName) {
            this.item = item;
            this.newBaseName = newBaseName;
        }
    }

    private static final class PendingTrimRequest {
        final RecordingItem item;
        final long startMs;
        final long endMs;
        final RecordingTrimmer.SaveMode mode;

        PendingTrimRequest(
                @NonNull final RecordingItem item,
                final long startMs,
                final long endMs,
                @NonNull final RecordingTrimmer.SaveMode mode
        ) {
            this.item = item;
            this.startMs = startMs;
            this.endMs = endMs;
            this.mode = mode;
        }
    }

    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger mDirectoryGeneration = new AtomicInteger(0);

    private RadioClient mRadioClient;
    private RecordingsAdapter mAdapter;
    private RecordingPlayerController mPlayerController;

    private MaterialToolbar mToolbar;
    private View mEmptyView;

    private String mCurrentDir = "";
    private int mSortOrder = RecordingsRepository.SORT_NEWEST_FIRST;
    private int mCurrentIndex = -1;
    @Nullable
    private String mActiveRelativePath;
    @Nullable
    private String mPendingOpenRelativePath;
    private boolean mPendingExpandPlayer = false;
    @Nullable
    private RecordingItem mPendingDeleteItem;
    @Nullable
    private String mPendingDeleteDir;
    @Nullable
    private PendingTrimRequest mPendingTrimRetry;
    @Nullable
    private PendingRenameRequest mPendingRenameRetry;
    @Nullable
    private AlertDialog mTrimProgressDialog;

    private final ActivityResultLauncher<IntentSenderRequest> mDeleteConsentLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            if (mPendingDeleteItem != null) {
                                onRecordingDeleted(mPendingDeleteItem);
                            } else if (mPendingDeleteDir != null) {
                                deleteDirectoryNow(mPendingDeleteDir);
                            } else if (mPendingTrimRetry != null) {
                                final PendingTrimRequest retry = mPendingTrimRetry;
                                mPendingTrimRetry = null;
                                executeTrim(retry.item, retry.startMs, retry.endMs, retry.mode);
                            } else if (mPendingRenameRetry != null) {
                                final PendingRenameRequest retry = mPendingRenameRetry;
                                mPendingRenameRetry = null;
                                executeRename(retry.item, retry.newBaseName);
                            }
                        }

                        mPendingDeleteItem = null;
                        mPendingDeleteDir = null;
                        mPendingTrimRetry = null;
                        mPendingRenameRetry = null;
                    }
            );

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
        mSortOrder = Storage.get(this).getRecordingsSort();

        initViews();
        initPlayerController();
        initBackHandling();

        mPendingExpandPlayer = getIntent().getBooleanExtra(EXTRA_EXPAND_PLAYER, false);
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
    protected void onNewIntent(@NonNull final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent.getBooleanExtra(EXTRA_EXPAND_PLAYER, false)) {
            mPlayerController.expand();
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
        dismissTrimProgress();
        mPlayerController.release();
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

        final RecyclerView recyclerView = findViewById(R.id.recordings_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
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
        recyclerView.setAdapter(mAdapter);
    }

    private void initPlayerController() {
        mPlayerController = new RecordingPlayerController(
                this,
                findViewById(R.id.recordings_root),
                new RecordingPlayerController.Host() {
                    @Override
                    public void onPlaybackStarting() {
                        stopLiveFmIfActive();
                    }

                    @Override
                    public void onNavigateAdjacent(final int delta) {
                        playAdjacent(delta);
                    }

                    @Override
                    public void onActiveItemChanged(@Nullable final String relativePath) {
                        mActiveRelativePath = relativePath;
                        mAdapter.setActiveRelativePath(relativePath);
                        syncCurrentIndexWithListing();
                    }

                    @Override
                    public void onShareRequested(@NonNull final RecordingItem item) {
                        shareRecording(item);
                    }

                    @Override
                    public void onRenameRequested(@NonNull final RecordingItem item) {
                        showRenameDialog(item);
                    }

                    @Override
                    public void onExportVideoRequested(
                            @NonNull final RecordingItem item,
                            @NonNull final RfmMetadata metadata,
                            final long durationMs
                    ) {
                        executeVideoExport(item, metadata, durationMs);
                    }

                    @Override
                    public void onDeleteRequested(@NonNull final RecordingItem item) {
                        confirmDeleteRecording(item);
                    }

                    @Override
                    public void onTrimRequested(
                            @NonNull final RecordingItem item,
                            final long startMs,
                            final long endMs,
                            @NonNull final RecordingTrimmer.SaveMode mode
                    ) {
                        executeTrim(item, startMs, endMs, mode);
                    }
                }
        );
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
        if (mPlayerController.handleBackPressed()) {
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
                syncCurrentIndexWithListing();

                if (mPendingOpenRelativePath != null) {
                    final String target = mPendingOpenRelativePath;
                    mPendingOpenRelativePath = null;
                    final List<RecordingItem> recs = listing.getRecordings();

                    for (int i = 0; i < recs.size(); i++) {
                        if (target.equals(recs.get(i).getRelativePath())) {
                            openRecording(recs.get(i), i, false);
                            if (mPendingExpandPlayer) {
                                mPendingExpandPlayer = false;
                                mPlayerController.expand();
                            }
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
        mActiveRelativePath = item.getRelativePath();
        mCurrentIndex = index;
        mPlayerController.open(item, autoPlay);
        updateAdjacentButtons();

        final String targetPath = item.getRelativePath();
        mIoExecutor.execute(() -> {
            RfmMetadata metadata = null;

            try {
                metadata = RecordingsRepository.readMetadata(this, item);
            } catch (final IOException ignored) {
            }

            final RfmMetadata loaded = metadata;
            mMainHandler.post(() -> mPlayerController.applyMetadata(targetPath, loaded));
        });
    }

    private void playAdjacent(final int delta) {
        final List<RecordingItem> recs = mAdapter.getRecordings();
        final int nextIdx = mCurrentIndex + delta;

        if (nextIdx < 0 || nextIdx >= recs.size()) {
            return;
        }

        openRecording(recs.get(nextIdx), nextIdx, true);
    }

    private void syncCurrentIndexWithListing() {
        mCurrentIndex = -1;

        if (mActiveRelativePath != null) {
            final List<RecordingItem> recs = mAdapter.getRecordings();
            for (int i = 0; i < recs.size(); i++) {
                if (mActiveRelativePath.equals(recs.get(i).getRelativePath())) {
                    mCurrentIndex = i;
                    break;
                }
            }
        }

        updateAdjacentButtons();
    }

    private void updateAdjacentButtons() {
        final int size = mAdapter.getRecordings().size();
        mPlayerController.setAdjacentEnabled(
                mCurrentIndex > 0 && mCurrentIndex < size,
                mCurrentIndex >= 0 && mCurrentIndex < size - 1
        );
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
        menu.add(0, MENU_RENAME, 2, R.string.recordings_action_rename);
        menu.add(0, MENU_DELETE, 3, R.string.record_delete_action);

        popup.setOnMenuItemClickListener(menuItem -> {
            final int id = menuItem.getItemId();

            if (id == MENU_SHARE) {
                shareRecording(target);
                return true;
            }

            if (id == MENU_RENAME) {
                showRenameDialog(target);
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

    private void showRenameDialog(@NonNull final RecordingItem target) {
        final String displayName = target.getDisplayName();
        final int dot = displayName.lastIndexOf('.');
        final String currentBase = dot > 0 ? displayName.substring(0, dot) : displayName;

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.recordings_rename_hint);
        input.setText(currentBase);
        input.setSelection(currentBase.length());
        input.setSingleLine(true);

        final int padH = Math.round(24f * getResources().getDisplayMetrics().density);
        final int padTop = Math.round(12f * getResources().getDisplayMetrics().density);
        final FrameLayout container = new FrameLayout(this);
        container.setPadding(padH, padTop, padH, 0);
        container.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recordings_rename_title)
                .setView(container)
                .setPositiveButton(R.string.recordings_action_rename, (d, which) -> {
                    final String entered = input.getText() != null ? input.getText().toString() : "";
                    final String clean = RecordingsRepository.sanitizeRenameBaseName(entered, target.getExtension());

                    if (clean == null) {
                        Toast.makeText(this, R.string.recordings_rename_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    executeRename(target, clean);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void executeRename(@NonNull final RecordingItem target, @NonNull final String newBaseName) {
        final String oldPath = target.getRelativePath();

        mIoExecutor.execute(() -> {
            try {
                final RecordingItem renamed = RecordingsRepository.rename(this, target, newBaseName);
                mMainHandler.post(() -> {
                    mPlayerController.onItemRenamed(oldPath, renamed);
                    Toast.makeText(this, R.string.recordings_rename_success, Toast.LENGTH_SHORT).show();
                    loadDirectory(mCurrentDir);
                });
            } catch (final SecurityException securityError) {
                if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        securityError instanceof RecoverableSecurityException
                ) {
                    final RecoverableSecurityException rse = (RecoverableSecurityException) securityError;
                    mMainHandler.post(() -> {
                        mPendingRenameRetry = new PendingRenameRequest(target, newBaseName);
                        final IntentSenderRequest request = new IntentSenderRequest.Builder(
                                rse.getUserAction().getActionIntent().getIntentSender()
                        ).build();
                        mDeleteConsentLauncher.launch(request);
                    });
                } else {
                    mMainHandler.post(() -> Toast.makeText(
                            this,
                            R.string.recordings_rename_failed,
                            Toast.LENGTH_LONG
                    ).show());
                }
            } catch (final IOException | IllegalArgumentException error) {
                mMainHandler.post(() -> Toast.makeText(
                        this,
                        R.string.recordings_rename_failed,
                        Toast.LENGTH_LONG
                ).show());
            }
        });
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
        mPlayerController.closeIfInDirectory(fullRelDir);

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

    private void executeTrim(
            @NonNull final RecordingItem source,
            final long startMs,
            final long endMs,
            @NonNull final RecordingTrimmer.SaveMode mode
    ) {
        mPlayerController.releasePlayer();

        dismissTrimProgress();
        mTrimProgressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recording_action_trim)
                .setMessage(getString(R.string.recording_trim_in_progress, 0))
                .setCancelable(false)
                .show();

        mIoExecutor.execute(() -> {
            try {
                final RecordingItem result = RecordingTrimmer.trim(
                        this,
                        source,
                        startMs,
                        endMs,
                        mode,
                        percent -> mMainHandler.post(() -> {
                            if (mTrimProgressDialog != null && mTrimProgressDialog.isShowing()) {
                                mTrimProgressDialog.setMessage(getString(R.string.recording_trim_in_progress, percent));
                            }
                        })
                );

                mMainHandler.post(() -> {
                    dismissTrimProgress();
                    mPlayerController.onTrimCompleted();
                    Toast.makeText(this, R.string.recording_trim_success, Toast.LENGTH_SHORT).show();
                    mPendingOpenRelativePath = result.getRelativePath();
                    loadDirectory(mCurrentDir);
                });
            } catch (final SecurityException securityError) {
                if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        securityError instanceof RecoverableSecurityException
                ) {
                    final RecoverableSecurityException rse = (RecoverableSecurityException) securityError;
                    mMainHandler.post(() -> {
                        dismissTrimProgress();
                        mPendingTrimRetry = new PendingTrimRequest(source, startMs, endMs, mode);
                        final IntentSenderRequest request = new IntentSenderRequest.Builder(
                                rse.getUserAction().getActionIntent().getIntentSender()
                        ).build();
                        mDeleteConsentLauncher.launch(request);
                    });
                } else {
                    mMainHandler.post(() -> {
                        dismissTrimProgress();
                        Toast.makeText(this, R.string.recording_trim_failed, Toast.LENGTH_LONG).show();
                        mPlayerController.onTrimFailed(source);
                    });
                }
            } catch (final IOException | RuntimeException error) {
                mMainHandler.post(() -> {
                    dismissTrimProgress();
                    Toast.makeText(this, R.string.recording_trim_failed, Toast.LENGTH_LONG).show();
                    mPlayerController.onTrimFailed(source);
                });
            }
        });
    }

    private void executeVideoExport(
            @NonNull final RecordingItem item,
            @NonNull final RfmMetadata metadata,
            final long durationMs
    ) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        dismissTrimProgress();
        mTrimProgressDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.recording_action_export_video)
                .setMessage(getString(R.string.recording_export_phase_encoding, 0, 0))
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, which) -> cancelled.set(true))
                .show();

        mIoExecutor.execute(() -> {
            try {
                final RecordingVideoExporter.ExportResult result = RecordingVideoExporter.export(
                        this,
                        item,
                        metadata,
                        durationMs,
                        (audioPercent, videoPercent, saving) -> mMainHandler.post(() -> {
                            if (mTrimProgressDialog == null || !mTrimProgressDialog.isShowing()) {
                                return;
                            }

                            final String msg = saving
                                    ? getString(R.string.recording_export_phase_saving)
                                    : getString(R.string.recording_export_phase_encoding, audioPercent, videoPercent);
                            mTrimProgressDialog.setMessage(msg);
                        }),
                        cancelled
                );

                mMainHandler.post(() -> {
                    dismissTrimProgress();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.recording_action_export_video)
                            .setMessage(getString(R.string.recording_export_video_success, result.displayName))
                            .setPositiveButton(R.string.recordings_action_share, (d, which) -> {
                                try {
                                    startActivity(RecordingVideoExporter.createShareIntent(
                                            this,
                                            result,
                                            getString(R.string.recordings_action_share)
                                    ));
                                } catch (final RuntimeException error) {
                                    Toast.makeText(this, R.string.recordings_share_failed, Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton(android.R.string.ok, null)
                            .show();
                });
            } catch (final CancellationException ignored) {
                mMainHandler.post(this::dismissTrimProgress);
            } catch (final IOException | RuntimeException error) {
                mMainHandler.post(() -> {
                    dismissTrimProgress();
                    Toast.makeText(this, R.string.recording_export_video_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void dismissTrimProgress() {
        if (mTrimProgressDialog != null) {
            if (mTrimProgressDialog.isShowing()) {
                mTrimProgressDialog.dismiss();
            }

            mTrimProgressDialog = null;
        }
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
        if (target.getRelativePath().equals(mActiveRelativePath)) {
            mPlayerController.pause();
        }

        try {
            if (RecordingsRepository.delete(this, target)) {
                onRecordingDeleted(target);
            } else {
                Toast.makeText(this, R.string.record_delete_failed, Toast.LENGTH_LONG).show();
            }
        } catch (final SecurityException securityError) {
            if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    securityError instanceof RecoverableSecurityException
            ) {
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
        mPlayerController.onItemDeleted(deleted.getRelativePath());
        loadDirectory(mCurrentDir);
    }
}
