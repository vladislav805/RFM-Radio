package com.vlad805.fmradio.service.fm.implementation;

import android.content.Context;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.service.fm.communication.Request;

import java.io.File;

/**
 * vlad805 (c) 2020
 */
public class QualcommLegacy extends AbstractQualcommNativeController {
	public QualcommLegacy(final Context context) {
		super(context);
	}

	/**
	 * Returns filename of binary by architecture
	 * @return Filename of binary
	 */
	@Override
	protected String getBinaryName() {
		return "fmbin-" + Utils.determineArch();
	}

	@Override
	protected String getBinaryVersionKey() {
		return C.PrefKey.BINARY_VERSION;
	}

	@Override
	protected void launchImpl(final Callback<Void> callback) {
		Utils.shell(String.format("%s 1>/dev/null 2>/dev/null &", getBinaryPath()), true);
		toggleCommandPoll(true);
		startServerListener();
		sendCommand(new Request("init", 1500).onResponse(data -> callback.onResult(null)));
	}

	@Override
	protected void onApplyAntennaPreference(final String value) {
		sendCommand(new Request("set_antenna " + value).onResponse(str -> {
			if (str.startsWith("ERR_UNV_ANT")) {
				fireEvent(C.Event.ERROR_INVALID_ANTENNA);
			}
		}));
	}

	@Override
	protected void setFrequencyImpl(final int kHz, final Callback<Integer> callback) {
		sendCommand(new Request("setfreq " + kHz).onResponse(data -> callback.onResult(kHz)));
	}

	public static boolean isAbleToWork() {
        return new File("/dev/radio0").exists();
    }
}
