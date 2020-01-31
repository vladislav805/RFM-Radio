package com.vlad805.fmradio.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.activity.MainActivity;
import com.vlad805.fmradio.enums.Direction;

import java.util.Locale;

/**
 * vlad805 (c) 2020
 */
@SuppressWarnings("deprecation")
public class RadioNotificationManager {
	public static final int NOTIFICATION_ID = 1027;
	private static final String CHANNEL_ID = "default_channel";
	private static final String TAG = "RNM";

	private final Context mContext;
	private final NotificationManagerCompat mNManager;
	private NotificationCompat.Builder mNBuilder;

	public RadioNotificationManager(final Context context) {
		mContext = context;
		mNManager = NotificationManagerCompat.from(context);
	}

	/**
	 * Create notification
	 * @return Notification builder
	 */
	private NotificationCompat.Builder createNotificationBuilder() {
		final Intent mainIntent = new Intent(mContext, MainActivity.class);
		mainIntent.setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

		final PendingIntent pendingMain = PendingIntent.getActivity(mContext, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);

		final PendingIntent pendingStop = PendingIntent.getService(mContext, 0, new Intent(mContext, FMService.class).setAction(C.Command.DISABLE), 0);
		final PendingIntent pendingRec = PendingIntent.getService(mContext, 0, new Intent(mContext, FMService.class).setAction(""), 0);
		final PendingIntent pendingSeekDown = PendingIntent.getService(mContext, 0, new Intent(mContext, FMService.class).setAction(C.Command.HW_SEEK).putExtra(C.Key.SEEK_HW_DIRECTION, Direction.DOWN), 0);
		final PendingIntent pendingSeekUp = PendingIntent.getService(mContext, 0, new Intent(mContext, FMService.class).setAction(C.Command.HW_SEEK).putExtra(C.Key.SEEK_HW_DIRECTION, Direction.UP), 0);

		/*Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
		bitmap.setPixel(0, 0, Color.BLACK);*/


		NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_radio)
				.setContentIntent(pendingMain)
				.setContentTitle(mContext.getString(R.string.app_name))
				.setContentText("Starting...")
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setShowWhen(false)
				.setAutoCancel(false)
				.setOngoing(true)
				.setColor(mContext.getResources().getColor(R.color.primary_blue))
				.addAction(R.drawable.ic_go_down, "Seek down", pendingSeekDown) // #0
				.addAction(R.drawable.ic_stop, "Pause", pendingStop) // #1
				.addAction(R.drawable.ic_cassette, "Rec", pendingRec) // #2
				.addAction(R.drawable.ic_go_up, "Seek up", pendingSeekUp) // #3
				.setStyle(new MediaStyle().setShowActionsInCompactView(1, 2))
				//.setLargeIcon(bitmap)
						//.setMediaSession(mediaSession.getSessionToken()))
				;

		return builder;

	}

	public Notification update(final Bundle state) {
		if (mNBuilder == null) {
			mNBuilder = createNotificationBuilder();
		}

		final int frequency = state.getInt(C.Key.FREQUENCY);
		final int rssi = state.getInt(C.Key.RSSI);
		mNBuilder
				.setSubText(String.format(Locale.ENGLISH, "%.1f MHz (RSSI = %d)", frequency / 1000d, rssi));

		final String rdsPs = state.getString(C.Key.PS);
		final String rdsRt = state.getString(C.Key.RT);

		if (rdsPs != null && !rdsPs.isEmpty()) {
			mNBuilder.setContentTitle(rdsPs);
		} else {
			mNBuilder.setContentTitle(mContext.getString(R.string.app_name));
		}

		if (rdsRt != null && !rdsRt.isEmpty()) {
			mNBuilder.setContentText(rdsRt);
		} else {
			mNBuilder.setContentText("");
		}

		final Notification q = mNBuilder.build();
		//mNManager.notify(NOTIFICATION_ID, q);
		return q;
	}
}
