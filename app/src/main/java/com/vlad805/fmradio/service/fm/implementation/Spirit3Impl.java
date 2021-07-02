package com.vlad805.fmradio.service.fm.implementation;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.fm.*;
import com.vlad805.fmradio.service.fm.communication.Poll;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;

/**
 * vlad805 (c) 2020
 */
public class Spirit3Impl extends AbstractFMController implements IFMEventPoller {
	private static final String TAG = "S3I";

	private static final LaunchBinaryConfig CONFIG = new LaunchBinaryConfig(2122, 0);

	private final Poll mCommandPoll;

	public Spirit3Impl(final Context context) {
		super(CONFIG, context);

		mCommandPoll = new Poll(CONFIG);
	}

	/**
	 * Returns filename of binary by architecture
	 * @return Filename of binary
	 */
	private String getBinaryName() {
		return "libs2d.so";
	}

	@SuppressLint("SdCardPath")
	private String getBinaryPath() {
		return "/data/data/fm.a2d.sf/lib/libs2d.so";
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
		callback.onResult(null);
	}

	@Override
	protected void launchImpl(final Callback<Void> callback) {
		String command = String.format("%s 4 1>/dev/null 2>/dev/null &", getBinaryPath());
		Utils.shell(command, true);
		callback.onResult(null);
	}

	@Override
	protected void applyPreferenceImpl(String key, String value) {
		Log.d(TAG, "applyPref " + key + " = " + value);
	}

	@Override
	protected void killImpl(final Callback<Void> callback) {
		String command = String.format("killall %s 1>/dev/null 2>/dev/null &", getBinaryName());
		Utils.shell(command, true);
		callback.onResult(null);
	}

	@Override
	protected void enableImpl(final Callback<Void> callback) {
		mCommandPoll.toggle(true);
		//noinspection CodeBlock2Expr
		sendCommand(new Request("s radio_nop start", 100).onResponse(v0 -> {
			sendCommand(new Request("s tuner_state start", 5000).onResponse(v1 -> callback.onResult(null)));
		}));
	}

	@Override
	protected void disableImpl(final Callback<Void> callback) {
		sendCommand(new Request("s tuner_state stop", 5000).onResponse(v -> callback.onResult(null)));
		mCommandPoll.toggle(false);
	}

	@Override
	protected void setFrequencyImpl(final int kHz, final Callback<Integer> callback) {
		sendCommand(new Request("s tuner_freq " + kHz).onResponse(res -> {
			callback.onResult(kHz); // res contains kHz?
		}));
	}

	/**
	 * Convert rssi to dB
	 * @param rssi Number
	 * @return Signal strength in dB
	 */
	private int fixRssi(int rssi) {
		// Spirit3 versions <= 3.0.12 has invalid normalize
		if (rssi > 0) {
			rssi = (139 + rssi);
		}

		return -0xff + rssi;
	}

	private String toDirection(int direction) {
		return direction > 0 ? "up" : "down";
	}

	@Override
	public void jumpImpl(final int direction, final Callback<Integer> callback) {
		sendCommand(new Request("g tuner_freq").onResponse(res -> {
			int frequency = Utils.parseInt(res);
			frequency += direction > 0 ? 100 : -100;
			setFrequency(frequency);
			callback.onResult(frequency);
		}));
	}

	@Override
	protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
		final Request request = new Request("s tuner_scan_state " + toDirection(direction), 15000);
		request.onResponse(res -> callback.onResult(Utils.parseInt(res)));
		sendCommand(request);
	}

	@Override
	protected void hwSearchImpl() {

	}

	@Override
	protected void setPowerModeImpl(final String mode) {
		// not supported
	}

	@Override
	public void setMute(final MuteState state, final Callback<Void> callback) {

	}

	private static final Request cmdTunerFreq = new Request("g tuner_freq");
	private static final Request cmdTunerRssi = new Request("g tuner_rssi");
	private static final Request cmdRdsPs = new Request("g rds_ps");

	@Override
	public void poll(final Callback<Bundle> callback) {
		final Bundle bundle = new Bundle();

		sendCommand(cmdTunerFreq.onResponse(freq -> {
			int frequency = Utils.parseInt(freq);
			bundle.putInt(C.Key.FREQUENCY, frequency);

			sendCommand(cmdTunerRssi.onResponse(sRssi -> {
				int rssi = Utils.parseInt(sRssi);

				bundle.putInt(C.Key.RSSI, fixRssi(rssi));

				sendCommand(cmdRdsPs.onResponse(ps -> {
					bundle.putString(C.Key.PS, ps);
					callback.onResult(bundle);
				}));
			}));
		}));
	}

	private void sendCommand(final Request request) {
		mCommandPoll.send(request);
	}

	public static boolean isAbleToWork() {
		//noinspection RedundantIfStatement
		if (!"qcom".equals(Build.HARDWARE) || !new File("/dev/radio0").exists()) {
			return false;
		}

		return true;
	}
}
