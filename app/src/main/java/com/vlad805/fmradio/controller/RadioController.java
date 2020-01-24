package com.vlad805.fmradio.controller;

import android.content.Context;
import android.content.Intent;
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

	public static RadioController getInstance(Context context) {
		if (sInstance == null) {
			sInstance = new RadioController(context);
		}

		return sInstance;
	}

	private Context mContext;
	private FMState mState;

	private RadioController(final Context context) {
		mContext = context;
		mState = new FMState();
	}

	public final FMState getState() {
		return mState;
	}

	private void send(String action) {
		send(action, new Intent(mContext, FMService.class));
	}

	private void send(String action, Intent intent) {
		Log.d("RadioController", "send(" + action + ", " + intent + ")");
		mContext.startService(intent.setAction(action));
	}

	public void setup() {
		send(C.Command.INIT);
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

	public void disable() {
		send(C.Command.DISABLE);
	}

	public void jump(Direction direction) {
		send(C.Command.JUMP, new Intent(mContext, FMService.class).putExtra(C.Key.JUMP_DIRECTION, direction.getValue()));
	}

	public void hwSeek(Direction direction) {
		send(C.Command.HW_SEEK, new Intent(mContext, FMService.class).putExtra(C.Key.SEEK_HW_DIRECTION, direction.getValue()));
	}

	public void setFrequency(int kHz) {
		send(C.Command.SET_FREQUENCY, new Intent(mContext, FMService.class).putExtra(C.Key.FREQUENCY, kHz));
	}

	public void onEvent(final Intent intent) {
		if (intent == null || intent.getAction() == null) {
			return;
		}

		switch (intent.getAction()) {
			case C.Event.BINARY_READY: mState.setMessage("Binary is ready"); break;
			case C.Event.READY: mState.setMessage("Ready for enable"); break;
			case C.Event.FM_READY: mState.setMessage(null); mState.setState(FMState.STATE_ON); break;
			case C.Event.FREQUENCY_SET: mState.setFrequency(intent.getIntExtra(C.Key.FREQUENCY, 1)); break;
			case C.Event.UPDATE_PS: mState.setPs(intent.getStringExtra(C.Key.PS)); break;
			case C.Event.UPDATE_RT: mState.setRt(intent.getStringExtra(C.Key.RT)); break;
			case C.Event.KILL: mState.setState(FMState.STATE_OFF); break;
		}

		//Log.d("RCS", "state = " + mState);
	}
}
