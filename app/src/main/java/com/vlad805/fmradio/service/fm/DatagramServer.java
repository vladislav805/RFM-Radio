package com.vlad805.fmradio.service.fm;

import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;

import static com.vlad805.fmradio.Utils.parseInt;

/**
 * vlad805 (c) 2019
 */
public class DatagramServer extends Thread {
	private final DatagramSocket mDatagramSocketServer;
	private FMEventCallback mCallback;

	private static final int BUFFER_SIZE = 96; // 2 + 1 + 4 * 20 + 1 = 84 as minimum

	private boolean mEnabled = false;

	private static final int EVT_ENABLED = 1;
	private static final int EVT_DISABLED = 2;

	private static final int EVT_FREQUENCY_SET = 4;
	private static final int EVT_UPDATE_PS = 6;
	private static final int EVT_UPDATE_RT = 7;
	private static final int EVT_SEEK_COMPLETE = 8;
	private static final int EVT_STEREO = 9;
	private static final int EVT_SEARCH_DONE = 10;
	private static final int EVT_UPDATE_PTY = 11;
	private static final int EVT_UPDATE_PI = 12;
	private static final int EVT_UPDATE_AF = 14;

	public DatagramServer(final int port) throws IOException {
		mDatagramSocketServer = new DatagramSocket(port);
	}

	public void setCallback(final FMEventCallback callback) {
		mCallback = callback;
	}

	@Override
	public void run() {
		mEnabled = true;

		byte[] buffer = new byte[BUFFER_SIZE];
		while (mEnabled) {
			DatagramPacket dp = new DatagramPacket(buffer, 0, BUFFER_SIZE);

			try {
				mDatagramSocketServer.receive(dp);

				String event = new String(dp.getData(), 0, dp.getLength());

				String result = handle(event);

				if (result != null) {
					dp.setData(result.getBytes());
					mDatagramSocketServer.send(dp);
				}
			} catch (SocketException e) {
				if (mEnabled) {
					e.printStackTrace();
				}
				mEnabled = false;
			} catch (IOException e) {
				if (mEnabled) {
					e.printStackTrace();
				}
			} catch (StopServer e) {
				mEnabled = false;
			}
		}
	}

	private static final char FF = 0x0c; // split for messages

	/**
	 * Handle event from native client (events such as RSSI change, RDS, etc...)
	 * @param data String in special format
	 * @return Response for native server
	 * @throws StopServer Throws when server should be killed
	 */
	private String handle(String data) throws StopServer {
		final int splitAt = data.indexOf(FF);
		if (splitAt <= 0) {
			Log.w("FMELS", "invalid event frame");
			return null;
		}

		final String code = data.substring(0, splitAt);
		data = data.substring(splitAt + 1);
		if (!isDigits(code)) {
			Log.w("FMELS", "invalid event code");
			return null;
		}

		int evt = parseInt(code);

		if (BuildConfig.DEBUG && false) {
			Log.i("FMELS", "received new event " + code + " = [" + data + "]");
		}

		final Bundle bundle = new Bundle();
		String action;

		switch (evt) {
			case EVT_ENABLED: {
				action = C.Event.ENABLED;
				break;
			}

			case EVT_DISABLED: {
				// action = C.Event.DISABLED;
				throw new StopServer();
			}

			case EVT_FREQUENCY_SET: {
				if (!isDigits(data.trim())) {
					Log.w("FMELS", "invalid frequency payload");
					return null;
				}

				action = C.Event.FREQUENCY_SET;
				bundle.putInt(C.Key.FREQUENCY, Utils.parseInt(data));
				break;
			}

			case EVT_UPDATE_PS: {
				action = C.Event.UPDATE_PS;
				bundle.putString(C.Key.PS, data.trim().replaceAll("\n", " "));
				break;
			}

			case EVT_UPDATE_RT: {
				action = C.Event.UPDATE_RT;
				bundle.putString(C.Key.RT, data.trim().replaceAll("\n", " "));
				break;
			}

			case EVT_SEARCH_DONE: {
				final int[] res = parseCompactFrequencyList(data.trim());
				if (res == null) {
					Log.w("FMELS", "invalid search result payload");
					return null;
				}

				Arrays.sort(res);

				action = C.Event.HW_SEARCH_DONE;
				bundle.putIntArray(C.Key.STATION_LIST, res);
				break;
			}

			case EVT_STEREO: {
				final String mode = data.trim();

				action = C.Event.UPDATE_STEREO;
				bundle.putBoolean(C.Key.STEREO_MODE, mode.equals("1"));
				break;
			}

			case EVT_UPDATE_PTY: {
				final String pty = data.trim();
				if (!pty.isEmpty() && !isDigits(pty)) {
					Log.w("FMELS", "invalid PTY payload");
					return null;
				}

				action = C.Event.UPDATE_PTY;

				bundle.putInt(C.Key.PTY, pty.isEmpty() ? 0 : Utils.parseInt(pty));
				break;
			}

			case EVT_UPDATE_PI: {
				action = C.Event.UPDATE_PI;

				bundle.putString(C.Key.PI, data);
				break;
			}

			case EVT_UPDATE_AF: {
				final int[] res = parseCompactFrequencyList(data.trim());
				if (res == null) {
					Log.w("FMELS", "invalid AF payload");
					return null;
				}

				action = C.Event.UPDATE_AF;
				bundle.putIntArray(C.Key.FREQUENCIES, res);
				break;
			}

			default: {
				Log.w("FMELS", "unknown event = " + evt);
				return null;
			}
		}

		if (mCallback != null) {
			mCallback.onEvent(action, bundle);
		}

		return "ok";
	}

	private static boolean isDigits(final String value) {
		if (value == null || value.isEmpty()) {
			return false;
		}

		for (int i = 0; i < value.length(); ++i) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}

		return true;
	}

	private static int[] parseCompactFrequencyList(final String payload) {
		final int lengthKHz = 4;
		if (payload.isEmpty()) {
			return new int[0];
		}

		if (payload.length() % lengthKHz != 0 || !isDigits(payload)) {
			return null;
		}

		final int count = payload.length() / lengthKHz;
		final int[] res = new int[count];
		for (int i = 0; i < count; ++i) {
			final int start = i * lengthKHz;
			res[i] = Utils.parseInt(payload.substring(start, start + lengthKHz)) * 100;
		}

		return res;
	}

	private static class StopServer extends Throwable { }

	public void closeServer() {
		mEnabled = false;
		if (!mDatagramSocketServer.isClosed()) {
			mDatagramSocketServer.close();
		}
	}
}
