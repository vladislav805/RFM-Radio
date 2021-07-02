package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import android.util.Log;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMEventListener;
import com.vlad805.fmradio.service.fm.LaunchBinaryConfig;

/**
 * vlad805 (c) 2020
 */
public class Empty extends AbstractFMController implements IFMEventListener {
	private static transient final String TAG = "FMCE";
	private static final LaunchBinaryConfig CONFIG = new LaunchBinaryConfig(1111, 1111);

	public Empty(final Context context) {
		super(CONFIG, context);
	}

	@Override
	public void setEventListener(final FMEventCallback callback) {

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
	protected void installImpl(final Callback<Void> callback) {
		Log.d(TAG, "install");
		callback.onResult(null);
	}

	@Override
	protected void applyPreferenceImpl(String key, String value) {
		Log.d(TAG, "applyPref " + key + " = " + value);
	}

	@Override
	public void launchImpl(final Callback<Void> callback) {
		Log.d(TAG, "launch");
		sleep(1000);
		callback.onResult(null);
	}

	@Override
	protected void killImpl(final Callback<Void> callback) {
		Log.d(TAG, "kill");
		sleep(1000);
		callback.onResult(null);
	}

	@Override
	protected void enableImpl(final Callback<Void> callback) {
		Log.d(TAG, "enable");
		sleep(1000);
		callback.onResult(null);
	}

	@Override
	protected void disableImpl(final Callback<Void> callback) {
		Log.d(TAG, "disable");
		sleep(1000);
		callback.onResult(null);
	}

	@Override
	protected void setFrequencyImpl(final int kHz, final Callback<Integer> callback) {
		Log.d(TAG, "setFrequency: " + kHz);
		sleep(200);
		callback.onResult(kHz);
	}

	@Override
	protected void jumpImpl(final int direction, final Callback<Integer> callback) {
		Log.d(TAG, "jump " + direction);
		sleep(1000);
		callback.onResult(87500);
	}

	@Override
	protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
		Log.d(TAG, "hwSeek " + direction);
		sleep(2000);
		callback.onResult(87500);
	}

	@Override
	protected void hwSearchImpl() {
		Log.d(TAG, "hwSearch");
	}

	@Override
	protected void setPowerModeImpl(String mode) {
		Log.d(TAG, "setPowerMode " + mode);
	}

	@Override
	public void setMute(final MuteState state, final Callback<Void> callback) {
		Log.d(TAG, "setMute " + state);
	}

	private void sleep(final int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public static boolean isAbleToWork() {
		return true;
	}
}
