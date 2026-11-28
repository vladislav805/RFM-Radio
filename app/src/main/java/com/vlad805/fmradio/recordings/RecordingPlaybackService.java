package com.vlad805.fmradio.recordings;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.vlad805.fmradio.R;
import com.vlad805.fmradio.activity.RecordingsActivity;

/**
 * Lightweight foreground service keeping local recording playback alive in the background
 * with a MediaStyle notification (Prev / Play-Pause / Next / Stop).
 */
public final class RecordingPlaybackService extends Service {
    public interface ControlsListener {
        void onTogglePlayPause();

        void onPrev();

        void onNext();

        void onStop();
    }

    private static final int NOTIFICATION_ID = 1031;
    private static final String CHANNEL_ID = "recording_playback_channel";

    private static final String ACTION_UPDATE = "com.vlad805.fmradio.recordings.ACTION_UPDATE";
    private static final String ACTION_TOGGLE_PLAY_PAUSE = "com.vlad805.fmradio.recordings.ACTION_TOGGLE_PLAY_PAUSE";
    private static final String ACTION_PREV = "com.vlad805.fmradio.recordings.ACTION_PREV";
    private static final String ACTION_NEXT = "com.vlad805.fmradio.recordings.ACTION_NEXT";
    private static final String ACTION_STOP = "com.vlad805.fmradio.recordings.ACTION_STOP";

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_SUBTITLE = "subtitle";
    private static final String EXTRA_RELATIVE_PATH = "relative_path";
    private static final String EXTRA_IS_PLAYING = "is_playing";
    private static final String EXTRA_HAS_PREV = "has_prev";
    private static final String EXTRA_HAS_NEXT = "has_next";

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private static volatile ControlsListener sControlsListener;
    private static volatile boolean sRunning = false;

    public static void setControlsListener(@Nullable final ControlsListener listener) {
        sControlsListener = listener;
    }

    public static void update(
            @NonNull final Context context,
            @NonNull final String title,
            @NonNull final String subtitle,
            @NonNull final String relativePath,
            final boolean isPlaying,
            final boolean hasPrev,
            final boolean hasNext
    ) {
        if (!isPlaying && !sRunning) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final Intent intent = new Intent(appContext, RecordingPlaybackService.class)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_RELATIVE_PATH, relativePath)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
                .putExtra(EXTRA_HAS_PREV, hasPrev)
                .putExtra(EXTRA_HAS_NEXT, hasNext);

        if (isPlaying) {
            ContextCompat.startForegroundService(appContext, intent);
        } else {
            appContext.startService(intent);
        }
    }

    public static void stop(@NonNull final Context context) {
        final Context appContext = context.getApplicationContext();
        sRunning = false;
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID);
        appContext.stopService(new Intent(appContext, RecordingPlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        switch (action) {
            case ACTION_UPDATE: {
                final String title = intent.getStringExtra(EXTRA_TITLE);
                final String subtitle = intent.getStringExtra(EXTRA_SUBTITLE);
                final String relativePath = intent.getStringExtra(EXTRA_RELATIVE_PATH);
                final boolean isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false);
                final boolean hasPrev = intent.getBooleanExtra(EXTRA_HAS_PREV, false);
                final boolean hasNext = intent.getBooleanExtra(EXTRA_HAS_NEXT, false);

                final Notification notification = buildNotification(
                        title != null ? title : getString(R.string.recordings_title),
                        subtitle != null ? subtitle : "",
                        relativePath,
                        isPlaying,
                        hasPrev,
                        hasNext
                );

                sRunning = true;

                if (isPlaying) {
                    final int serviceType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            : 0;
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType);
                } else {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification);
                }
                break;
            }

            case ACTION_TOGGLE_PLAY_PAUSE: {
                final ControlsListener listener = sControlsListener;
                if (listener != null) {
                    sMainHandler.post(listener::onTogglePlayPause);
                } else {
                    stopAndDismiss();
                }
                break;
            }

            case ACTION_PREV: {
                final ControlsListener listener = sControlsListener;
                if (listener != null) {
                    sMainHandler.post(listener::onPrev);
                }
                break;
            }

            case ACTION_NEXT: {
                final ControlsListener listener = sControlsListener;
                if (listener != null) {
                    sMainHandler.post(listener::onNext);
                }
                break;
            }

            case ACTION_STOP: {
                final ControlsListener listener = sControlsListener;
                if (listener != null) {
                    sMainHandler.post(listener::onStop);
                }
                stopAndDismiss();
                break;
            }

            default: {
                break;
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        super.onDestroy();
    }

    private void stopAndDismiss() {
        sRunning = false;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recordings_title),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.enableVibration(false);
        channel.setVibrationPattern(new long[0]);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification buildNotification(
            @NonNull final String title,
            @NonNull final String subtitle,
            @Nullable final String relativePath,
            final boolean isPlaying,
            final boolean hasPrev,
            final boolean hasNext
    ) {
        final Intent openIntent = new Intent(this, RecordingsActivity.class)
                .putExtra(RecordingsActivity.EXTRA_EXPAND_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (relativePath != null && !relativePath.isEmpty()) {
            openIntent.putExtra(RecordingsActivity.EXTRA_INITIAL_RELATIVE_PATH, relativePath);
        }
        final PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                10,
                openIntent,
                FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
        );

        final PendingIntent toggleIntent = serviceAction(11, ACTION_TOGGLE_PLAY_PAUSE);
        final PendingIntent stopIntent = serviceAction(12, ACTION_STOP);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_file_music)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(contentIntent)
                .setDeleteIntent(stopIntent)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(isPlaying);

        int actionIdx = 0;
        int prevIdx = -1;
        int playPauseIdx;
        int nextIdx = -1;
        int stopIdx;

        if (hasPrev) {
            builder.addAction(
                    R.drawable.ic_rfm_seek_prev,
                    getString(R.string.recordings_prev),
                    serviceAction(13, ACTION_PREV)
            );
            prevIdx = actionIdx++;
        }

        builder.addAction(
                isPlaying ? R.drawable.ic_rfm_pause : R.drawable.ic_rfm_play,
                getString(R.string.toggle_play_pause),
                toggleIntent
        );
        playPauseIdx = actionIdx++;

        if (hasNext) {
            builder.addAction(
                    R.drawable.ic_rfm_seek_next,
                    getString(R.string.recordings_next),
                    serviceAction(14, ACTION_NEXT)
            );
            nextIdx = actionIdx++;
        }

        builder.addAction(
                R.drawable.ic_close,
                getString(android.R.string.cancel),
                stopIntent
        );
        stopIdx = actionIdx;

        final int[] compactIndices;
        if (prevIdx >= 0 && nextIdx >= 0) {
            compactIndices = new int[]{prevIdx, playPauseIdx, nextIdx};
        } else if (prevIdx >= 0) {
            compactIndices = new int[]{prevIdx, playPauseIdx, stopIdx};
        } else if (nextIdx >= 0) {
            compactIndices = new int[]{playPauseIdx, nextIdx, stopIdx};
        } else {
            compactIndices = new int[]{playPauseIdx, stopIdx};
        }

        builder.setStyle(
                new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(compactIndices)
        );

        return builder.build();
    }

    @NonNull
    private PendingIntent serviceAction(final int requestCode, @NonNull final String action) {
        return PendingIntent.getService(
                this,
                requestCode,
                new Intent(this, RecordingPlaybackService.class).setAction(action),
                FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
        );
    }
}
