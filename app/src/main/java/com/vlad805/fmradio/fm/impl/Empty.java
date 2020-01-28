package com.vlad805.fmradio.fm.impl;

import android.content.Context;
import android.util.Log;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.fm.FMController;
import com.vlad805.fmradio.fm.FMEventCallback;
import com.vlad805.fmradio.fm.IFMEventListener;
import com.vlad805.fmradio.fm.LaunchConfig;

/**
 * vlad805 (c) 2020
 */
public class Empty extends FMController implements IFMEventListener {
	private static transient final String TAG = "FMCE";

	public static class Config extends LaunchConfig {
	}

	private FMEventCallback mEventCallback;

	public Empty(final LaunchConfig config, final Context context) {
		super(config, context);
	}

	@Override
	public void setEventListener(FMEventCallback callback) {
		mEventCallback = callback;
	}

	@Override
	public boolean isInstalled() {
		return true;
	}

	@Override
	public boolean isObsolete() {
		return true;
	}

	@Override
	protected boolean installImpl() {
		Log.d(TAG, "install");
		return true;
	}

	@Override
	public boolean launchImpl() {
		Log.d(TAG, "launch");
		return true;
	}

	@Override
	protected boolean killImpl() {
		Log.d(TAG, "kill");
		return true;
	}

	@Override
	protected boolean enableImpl() {
		Log.d(TAG, "enable");
		return true;
	}

	@Override
	protected boolean disableImpl() {
		Log.d(TAG, "disable");
		return true;
	}

	@Override
	protected boolean setFrequencyImpl(final int kHz) {
		Log.d(TAG, "setFrequency: " + kHz);
		return true;
	}

	@Override
	protected int getSignalStretchImpl() {
		Log.d(TAG, "getSignalStretch");
		return 777;
	}

	@Override
	protected int jumpImpl(final int direction) {
		Log.d(TAG, "jump " + direction);
		return 87500;
	}

	@Override
	protected int hwSeekImpl(final int direction) {
		Log.d(TAG, "hwSeek " + direction);
		return 87500;
	}

	@Override
	public void setMute(MuteState state) {
		Log.d(TAG, "setMute " + state);
	}

	@Override
	public void search() {
		Log.d(TAG, "search");
	}
}
