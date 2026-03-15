package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.fm.FMEventCallback;
import com.vlad805.fmradio.service.fm.IFMController;
import com.vlad805.fmradio.service.fm.IFMEventListener;

/**
 * vlad805 (c) 2020
 */
public class Empty implements IFMController, IFMEventListener {
	private static final String TAG = "FMCE";
	private final Context context;

	public Empty(final Context context) {
		this.context = context;
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
	public void install() {
		Log.d(TAG, "install");
		fireEvent(C.Event.INSTALLING);
		fireEvent(C.Event.INSTALLED);
	}

	@Override
	public void applyPreference(final String key, final String value) {
		Log.d(TAG, "applyPref " + key + " = " + value);
	}

	@Override
	public void prepareBinary() {
		fireEvent(C.Event.PREPARING);
		fireEvent(C.Event.INSTALLED);
	}

	@Override
	public void launch() {
		Log.d(TAG, "launch");
		sleep(1000);
		fireEvent(C.Event.LAUNCHING);
		fireEvent(C.Event.LAUNCHED);
	}

	@Override
	public void kill() {
		Log.d(TAG, "kill");
		sleep(1000);
		fireEvent(C.Event.DISABLED);
		fireEvent(C.Event.KILLED);
	}

	@Override
	public void enable() {
		Log.d(TAG, "enable");
		sleep(1000);
		fireEvent(C.Event.ENABLING);
		fireEvent(C.Event.ENABLED);
	}

	@Override
	public void setupTunerByPreferences(final String[] changed) {
	}

	@Override
	public void disable() {
		Log.d(TAG, "disable");
		sleep(1000);
		fireEvent(C.Event.DISABLING);
		fireEvent(C.Event.DISABLED);
	}

	@Override
	public void setFrequency(final int kHz) {
		Log.d(TAG, "setFrequency: " + kHz);
		sleep(200);
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.FREQUENCY, kHz);
		fireEvent(C.Event.FREQUENCY_SET, bundle);
	}

	@Override
	public void jump(final int direction) {
		Log.d(TAG, "jump " + direction);
		sleep(1000);
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.FREQUENCY, 87500);
		fireEvent(C.Event.JUMP_COMPLETE, bundle);
	}

	@Override
	public void hwSeek(final int direction) {
		Log.d(TAG, "hwSeek " + direction);
		sleep(2000);
		final Bundle bundle = new Bundle();
		bundle.putInt(C.Key.FREQUENCY, 87500);
		fireEvent(C.Event.HW_SEEK_COMPLETE, bundle);
	}

	@Override
	public void hwSearch() {
		Log.d(TAG, "hwSearch");
	}

	@Override
	public void setPowerMode(final String mode) {
		Log.d(TAG, "setPowerMode " + mode);
	}

	private void sleep(final int ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unused")
    public static boolean isAbleToWork() {
		return true;
	}

	private void fireEvent(final String event) {
		context.sendBroadcast(new Intent(event));
	}

	private void fireEvent(final String event, final Bundle bundle) {
		context.sendBroadcast(new Intent(event).putExtras(bundle));
	}
}
