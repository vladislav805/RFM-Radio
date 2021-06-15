package com.vlad805.fmradio.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.telephony.TelephonyManager;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.FMService;

import static android.content.Context.TELEPHONY_SERVICE;

/**
 * Declared as receiver in AndroidManifest.xml and passed to (un)registerMediaButtonEventReceiver
 */
public class CallReceiver extends BroadcastReceiver {
	private static final String TAG = "CR";

	/**
	 * Need empty constructor since system will start via AndroidManifest.xml, before app ever starts
	 */
	public CallReceiver() {}

	/**
	 * Media buttons
	 */
	@Override
	public void onReceive(final Context context, final Intent intent) {
		final String action = intent.getAction();
		if (action == null) {
			return;
		}

		switch (action) {
			case AudioManager.ACTION_AUDIO_BECOMING_NOISY: {
				break;
			}

			case Intent.ACTION_NEW_OUTGOING_CALL: {
				stopRadio(context);
				break;
			}

			default: {
				handleCall(context);
			}
		}
	}

	private void handleCall(final Context context) {
		final TelephonyManager tm = (TelephonyManager) context.getSystemService(TELEPHONY_SERVICE);
		switch (tm.getCallState()) {
			case TelephonyManager.CALL_STATE_OFFHOOK:
			case TelephonyManager.CALL_STATE_RINGING:
				stopRadio(context);
				break;

			case TelephonyManager.CALL_STATE_IDLE:
				// TODO: restore play if was playing
				break;
		}
	}

	private void stopRadio(final Context context) {
		context.startService(new Intent(context, FMService.class).setAction(C.Command.KILL));
	}
}
