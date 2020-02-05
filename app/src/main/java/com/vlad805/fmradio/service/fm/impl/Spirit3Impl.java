package com.vlad805.fmradio.service.fm.impl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.FMRecordService;
import com.vlad805.fmradio.service.fm.*;
import com.vlad805.fmradio.service.fm.communications.Poll;
import com.vlad805.fmradio.service.fm.communications.Request;

import java.util.List;

/**
 * vlad805 (c) 2020
 */
public class Spirit3Impl extends FMController implements IFMEventPoller, IFMRecordable {
	private static final String TAG = "S3I";

	public static class Config extends LaunchConfig {
		@Override
		public int getClientPort() {
			return 2122;
		}
	}

	private Poll mCommandPoll;

	public Spirit3Impl(final LaunchConfig config, final Context context) {
		super(config, context);
		mCommandPoll = new Poll(config);
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
	protected void killImpl(final Callback<Void> callback) {
		String command = String.format("killall %s 1>/dev/null 2>/dev/null &", getBinaryName());
		Utils.shell(command, true);
		callback.onResult(null);
	}

	@Override
	protected void enableImpl(final Callback<Void> callback) {
		//noinspection CodeBlock2Expr
		sendCommand(new Request("s radio_nop start", 100).onResponse(v0 -> {
			sendCommand(new Request("s tuner_state start", 5000).onResponse(v1 -> callback.onResult(null)));
		}));
	}

	@Override
	protected void disableImpl(final Callback<Void> callback) {
		sendCommand(new Request("s tuner_state stop", 5000).onResponse(v -> callback.onResult(null)));
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

	@Override
	public void getSignalStretchImpl(final Callback<Integer> callback) {
		sendCommand(cmdTunerRssi.onResponse(sRssi -> {
			int rssi = Utils.parseInt(sRssi);

			callback.onResult(fixRssi(rssi));
		}));
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
		//noinspection CodeBlock2Expr
		sendCommand(new Request("s tuner_scan_state " + toDirection(direction), 15000).onResponse(res -> {
			callback.onResult(Utils.parseInt(res));
		}));
	}

	@Override
	public void setMute(final MuteState state, final Callback<Void> callback) {

	}

	@Override
	public void search(final Callback<List<Integer>> callback) {

	}

	@Override
	public void newRecord(final Callback<IFMRecorder> callback) {
		sendCommand(cmdTunerFreq.onResponse(freq -> callback.onResult(new FMRecordService(context, Utils.parseInt(freq)))));
	}

	private static Request cmdTunerFreq = new Request("g tuner_freq");
	private static Request cmdTunerRssi = new Request("g tuner_rssi");
	private static Request cmdRdsPs = new Request("g rds_ps");

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
}
