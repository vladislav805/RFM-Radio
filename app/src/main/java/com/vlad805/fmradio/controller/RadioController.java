package com.vlad805.fmradio.controller;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.fm.FMState;
import com.vlad805.fmradio.service.FMService;

/**
 * vlad805 (c) 2020
 */
public class RadioController {

	private static RadioController sInstance;

	public static RadioController getInstance() {
		if (sInstance == null) {
			sInstance = new RadioController();
		}

		return sInstance;
	}

	private FMState mState;

	private RadioController() {
		mState = FMState.getInstance();
	}

	public final FMState getState() {
		return mState;
	}

	private void send(final Context context, final String action) {
		send(context, action, new Bundle());
	}

	private void send(final Context context, final String action, final Bundle bundle) {
		Log.d("RC", "Command '" + action + "' sending... with bundle " + bundle + ")");
		context.startService(new Intent(context, FMService.class).setAction(action).putExtras(bundle));
	}

	public void setup(final Context context) {
		send(context, C.Command.SETUP);
	}

	public void launch(final Context context) {
		send(context, C.Command.LAUNCH);
	}

	public void kill(final Context context) {
		send(context, C.Command.KILL);
	}

	public void enable(final Context context) {
		send(context, C.Command.ENABLE);
	}

	public void setFrequency(final Context context, final int kHz) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.FREQUENCY, kHz);
		send(context, C.Command.SET_FREQUENCY, bundle);
	}

	public void jump(final Context context, final Direction direction) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.JUMP_DIRECTION, direction.getValue());
		send(context, C.Command.JUMP, bundle);
	}

	public void hwSeek(final Context context, final Direction direction) {
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.SEEK_HW_DIRECTION, direction.getValue());
		send(context, C.Command.HW_SEEK, bundle);
	}

	public void disable(final Context context) {
		send(context, C.Command.DISABLE);
	}



	public void onEvent(final Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return;
		}

		switch (intent.getAction()) {
			case C.Event.BINARY_READY: mState.setMessage("Binary is ready"); break;
			case C.Event.READY: mState.setMessage("Ready for enable"); break;
			case C.Event.FM_READY: mState.setMessage(null); mState.addState(FMState.STATE_LAUNCHED); break;
			case C.Event.ENABLED: mState.addState(FMState.STATE_ENABLED); break;
			case C.Event.DISABLED: mState.removeState(FMState.STATE_ENABLED); break;
			case C.Event.KILL: mState.setState(FMState.STATE_OFF); break;

			case C.Event.FREQUENCY_SET: mState.setFrequency(intent.getIntExtra(C.Key.FREQUENCY, 1)); break;
			case C.Event.UPDATE_PS: mState.setPs(intent.getStringExtra(C.Key.PS)); break;
			case C.Event.UPDATE_RT: mState.setRt(intent.getStringExtra(C.Key.RT)); break;
		}

		//Log.d("RCS", "state = " + mState);
	}
}
