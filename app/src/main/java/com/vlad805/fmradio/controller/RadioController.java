package com.vlad805.fmradio.controller;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.service.FMService;

/**
 * vlad805 (c) 2020
 */
public class RadioController {
	private final Context mContext;
	private final Bundle mState;

	public RadioController(final Context context) {
		mContext = context;
		mState = new Bundle();
	}

	public final Bundle getState() {
		return mState;
	}

	private void send(final String action) {
		send(action, new Bundle());
	}

	private void send(final String action, final Bundle bundle) {
		mContext.startService(new Intent(mContext, FMService.class).setAction(action).putExtras(bundle));
	}

	public void setup() {
		send(C.Command.SETUP);
	}

	public void launch() {
		send(C.Command.LAUNCH);
	}

	public void kill() {
		send(C.Command.KILL);
	}

	public void enable() {
		send(C.Command.ENABLE);
	}

	public void setFrequency(final int kHz) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.FREQUENCY, kHz);
		send(C.Command.SET_FREQUENCY, bundle);
	}

	public void jump(final Direction direction) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.JUMP_DIRECTION, direction.getValue());
		send(C.Command.JUMP, bundle);
	}

	public void hwSeek(final Direction direction) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.SEEK_HW_DIRECTION, direction.getValue());
		send(C.Command.HW_SEEK, bundle);
	}

	public void disable() {
		send(C.Command.DISABLE);
	}

	public void onEvent(@NonNull final Intent intent) {
		if (intent.getAction() == null) {
			return;
		}

		switch (intent.getAction()) {
			case C.Event.INSTALLED: {
				mState.putString(C.Key.MESSAGE, "Binary is ready");
				break;
			}

			case C.Event.LAUNCHED: {
				mState.putString(C.Key.MESSAGE, "Ready for enable");
				break;
			}

			case C.Event.ENABLED: {
				mState.putBoolean(C.Key.STATE_ENABLE, true);
				break;
			}

			case C.Event.DISABLED: {
				mState.remove(C.Key.STATE_ENABLE);
				break;
			}

			case C.Event.KILLED: {
				mState.remove(C.Key.STATE_ENABLE);
				mState.remove(C.Key.STATE_LAUNCH);
				break;
			}

			case C.Event.FREQUENCY_SET: {
				int frequency = intent.getIntExtra(C.Key.FREQUENCY, 1);
				mState.putInt(C.Key.FREQUENCY, frequency);
				break;
			}

			case C.Event.UPDATE_PS: {
				String ps = intent.getStringExtra(C.Key.PS);
				mState.putString(C.Key.PS, ps);
				break;
			}

			case C.Event.UPDATE_RT: {
				String rt = intent.getStringExtra(C.Key.RT);
				mState.putString(C.Key.RT, rt);
				break;
			}
		}

		//Log.d("RCS", "state = " + mState);
	}
}
