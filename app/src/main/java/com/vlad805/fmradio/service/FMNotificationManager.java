package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.activity.RecordingDeleteActivity;
import com.vlad805.fmradio.service.recording.RecordingResult;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static com.vlad805.fmradio.Utils.getTimeStringBySeconds;

public final class FMNotificationManager {
    public static final int NOTIFICATION_ID = 1027;
    public static final int NOTIFICATION_RECORD_ID = 1029;
    static final String CHANNEL_ID = "playback_channel_v3";
    static final String CHANNEL_RECORD_ID = "record_channel";
    static final String CHANNEL_RECORDING_ID = "recording_channel_v2";

    private final Context mContext;
    private final NotificationManagerCompat mNotificationManager;
    private NotificationCompat.Builder mPlaybackBuilder;
    private Boolean mLastRecordingActive;

    public FMNotificationManager(final Context context) {
        mContext = context;
        mNotificationManager = NotificationManagerCompat.from(context);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private static void makeChannelSilent(final NotificationChannel channel) {
        channel.enableVibration(false);
        channel.setVibrationPattern(new long[0]);
        channel.setSound(null, null);
    }

    public void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        final NotificationManager notificationManager = mContext.getSystemService(NotificationManager.class);

        if (notificationManager == null) {
            return;
        }

        final NotificationChannel mainChannel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        mainChannel.setDescription(mContext.getString(R.string.app_name));
        makeChannelSilent(mainChannel);

        final NotificationChannel recordChannel = new NotificationChannel(
                CHANNEL_RECORD_ID,
                mContext.getString(R.string.app_name) + " recordings",
                NotificationManager.IMPORTANCE_LOW
        );
        recordChannel.setDescription(mContext.getString(R.string.notification_recorded, "", 0f, ""));
        makeChannelSilent(recordChannel);

        final NotificationChannel recordingChannel = new NotificationChannel(
                CHANNEL_RECORDING_ID,
                mContext.getString(R.string.app_name) + " recording",
                NotificationManager.IMPORTANCE_HIGH
        );
        recordingChannel.setDescription(mContext.getString(R.string.notification_recording, "", 0f));
        makeChannelSilent(recordingChannel);

        notificationManager.createNotificationChannel(mainChannel);
        notificationManager.createNotificationChannel(recordChannel);
        notificationManager.createNotificationChannel(recordingChannel);
    }

    public Notification buildPlayback(
            final int frequencyKhz,
            final String ps,
            final String rt,
            final String favoriteTitle,
            final boolean showRds,
            final boolean recordingActive
    ) {
        if (mPlaybackBuilder == null || mLastRecordingActive == null || mLastRecordingActive != recordingActive) {
            mPlaybackBuilder = createPlaybackBuilder(recordingActive);
            mLastRecordingActive = recordingActive;
        }

        final String frequencyLabel = mContext.getString(R.string.notification_mhz, frequencyKhz / 1000d);
        mPlaybackBuilder.setSubText(frequencyLabel);
        mPlaybackBuilder.setContentTitle(formatPlaybackTitle(frequencyLabel, ps, favoriteTitle, showRds));
        mPlaybackBuilder.setContentText(formatPlaybackBody(rt, showRds));

        final Notification notification = mPlaybackBuilder.build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        return notification;
    }

    static String formatPlaybackTitle(
            final String frequencyLabel,
            final String ps,
            final String favoriteTitle,
            final boolean showRds
    ) {
        String title = frequencyLabel.trim();
        if (showRds && ps != null && !ps.isEmpty()) {
            title = title + " | " + ps.trim();
        } else if (favoriteTitle != null) {
            title = title + " | " + favoriteTitle;
        }
        return title;
    }

    static String formatPlaybackBody(final String rt, final boolean showRds) {
        return showRds && rt != null && !rt.isEmpty() ? rt : "";
    }

    static int[] compactActionIndices(final boolean recordingActive) {
        return recordingActive ? new int[]{1} : new int[]{1, 2};
    }

    static String recordingNotificationTag(@NonNull final RecordingResult result) {
        return recordingNotificationTag(result.getRecordingUri(), result.getRecordingFilePath());
    }

    static String recordingNotificationTag(final String uri, final String filePath) {
        final String location = uri != null ? uri : filePath;
        return "recording:" + location;
    }

    @SuppressWarnings("deprecation")
    private NotificationCompat.Builder createPlaybackBuilder(final boolean recordingActive) {
        final Intent mainIntent = new Intent(mContext, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);

        final PendingIntent pendingMain = PendingIntent.getActivity(
                mContext,
                0,
                mainIntent,
                FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
        );

        final PendingIntent pendingStop = PendingIntent.getService(
                mContext,
                1,
                new Intent(mContext, FMService.class).setAction(C.Command.DISABLE),
                FLAG_IMMUTABLE
        );

        final PendingIntent pendingRec = PendingIntent.getService(
                mContext,
                2,
                new Intent(mContext, FMService.class)
                        .setAction(C.Command.RECORD_START),
                FLAG_IMMUTABLE
        );

        final PendingIntent pendingSeekDown = PendingIntent.getService(
                mContext,
                3,
                new Intent(mContext, FMService.class)
                        .setAction(C.Command.NOTIFICATION_SEEK)
                        .putExtra(C.Key.SEEK_HW_DIRECTION, -1),
                FLAG_IMMUTABLE
        );

        final PendingIntent pendingSeekUp = PendingIntent.getService(
                mContext,
                4,
                new Intent(mContext, FMService.class)
                        .setAction(C.Command.NOTIFICATION_SEEK)
                        .putExtra(C.Key.SEEK_HW_DIRECTION, 1),
                FLAG_IMMUTABLE
        );

        final androidx.media.app.NotificationCompat.MediaStyle ms =
                new androidx.media.app.NotificationCompat.MediaStyle();
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentIntent(pendingMain)
                .setContentTitle(mContext.getString(R.string.app_name))
                .setContentText(mContext.getString(R.string.progress_starting))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(false)
                .setOngoing(true)
                .setColor(mContext.getResources().getColor(R.color.primary_blue))
                .addAction(R.drawable.ic_go_down, mContext.getString(R.string.seek_down), pendingSeekDown) // #0
                .addAction(R.drawable.ic_stop, mContext.getString(R.string.toggle_play_pause), pendingStop); // #1

        if (!recordingActive) {
            builder.addAction(R.drawable.ic_record_off, mContext.getString(R.string.menu_record), pendingRec); // #2
        }
        ms.setShowActionsInCompactView(compactActionIndices(recordingActive));

        builder.addAction(R.drawable.ic_go_up, mContext.getString(R.string.seek_up), pendingSeekUp) // #3 or #2
                .setStyle(ms);

        return builder;
    }

    @SuppressWarnings("deprecation")
    public void showRecordingProgress(final int durationSeconds, final int sizeBytes) {
        final PendingIntent pendingRecordStop = PendingIntent.getService(
                mContext,
                5,
                new Intent(mContext, FMService.class)
                        .setAction(C.Command.RECORD_STOP),
                FLAG_IMMUTABLE
        );
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_RECORDING_ID)
                .setSmallIcon(R.drawable.ic_record_on)
                .setContentTitle(mContext.getString(R.string.app_name))
                .setContentText(
                        mContext.getString(
                                R.string.notification_recording,
                                getTimeStringBySeconds(durationSeconds),
                                sizeBytes / 1024f / 1024f
                        )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(false)
                .setOngoing(true)
                .setColor(mContext.getResources().getColor(R.color.record_active))
                .addAction(R.drawable.ic_record_off, mContext.getString(R.string.seek_down), pendingRecordStop)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0));

        mNotificationManager.notify(NOTIFICATION_RECORD_ID, builder.build());
    }

    public void cancelRecordingProgress() {
        mNotificationManager.cancel(NOTIFICATION_RECORD_ID);
    }

    public void showRecordingResult(@NonNull final RecordingResult result, final boolean showSavedNotification) {
        final int size = result.getSizeBytes();
        final int duration = result.getDurationSeconds();
        final String filename = result.getDisplayName();
        final String uri = result.getRecordingUri();
        final String filePath = result.getRecordingFilePath();
        final String subtitleUri = result.getSubtitleUri();
        final String subtitlePath = result.getSubtitleFilePath();

        if (showSavedNotification) {
            final String location = result.getLocation();
            final String notificationTag = recordingNotificationTag(result);
            final Intent deleteIntent = RecordingDeleteActivity.createIntent(
                    mContext,
                    uri,
                    filePath,
                    filename,
                    notificationTag,
                    subtitleUri,
                    subtitlePath
            );
            final PendingIntent pendingDelete = PendingIntent.getActivity(
                    mContext,
                    location != null ? location.hashCode() : 0,
                    deleteIntent,
                    FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE
            );
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_RECORD_ID)
                    .setSmallIcon(R.drawable.ic_radio)
                    .setContentTitle(mContext.getString(R.string.app_name))
                    .setContentText(
                            mContext.getString(
                                    R.string.notification_recorded,
                                    getTimeStringBySeconds(duration),
                                    size / 1024f / 1024f,
                                    filename
                            )
                    )
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .addAction(
                            R.drawable.ic_favorite_list_remove,
                            mContext.getString(R.string.record_delete_action),
                            pendingDelete
                    );

            mNotificationManager.notify(notificationTag, RecordingDeleteActivity.NOTIFICATION_ID, builder.build());
        }

        String message = mContext.getString(
                R.string.toast_record_ended,
                filename,
                size / 1024f / 1024f,
                getTimeStringBySeconds(duration)
        );

        if (result.hasSubtitleFailure()) {
            message += "\n" + mContext.getString(R.string.record_subtitles_failed);
        }

        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }
}
