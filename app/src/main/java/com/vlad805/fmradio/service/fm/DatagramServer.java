package com.vlad805.fmradio.service.fm;

import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
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
	private static final int EVT_UPDATE_RSSI = 5;
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
			} catch (IOException e) {
				e.printStackTrace();
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
		final String code = data.substring(0, splitAt);
		data = data.substring(splitAt + 1);

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
				action = C.Event.FREQUENCY_SET;
				bundle.putInt(C.Key.FREQUENCY, Utils.parseInt(data));
				break;
			}

			case EVT_UPDATE_RSSI: {
				action = C.Event.UPDATE_RSSI;
				int val = Utils.parseInt(data);
				val = -0xff + val;
				bundle.putInt(C.Key.RSSI, val);
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
				final String stations = data.trim();
				final int lengthKHz = 4;
				final int count = stations.length() / lengthKHz;

				final int[] res = new int[count];

				for (int i = 0; i < count; ++i) {
					int start = i * lengthKHz;
					res[i] = Utils.parseInt(stations.substring(start, start + lengthKHz)) * 100;
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
				action = C.Event.UPDATE_PTY;

				bundle.putInt(C.Key.PTY, Utils.parseInt(data));
				break;
			}

			case EVT_UPDATE_PI: {
				action = C.Event.UPDATE_PI;

				bundle.putString(C.Key.PI, data);
				break;
			}

			case EVT_UPDATE_AF: {
				final String frequencies = data.trim();
				final int lengthKHz = 4;
				final int count = frequencies.length() / lengthKHz;

				final int[] res = new int[count];

				for (int i = 0; i < count; ++i) {
					int start = i * lengthKHz;
					res[i] = Utils.parseInt(frequencies.substring(start, start + lengthKHz)) * 100;
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

	private static class StopServer extends Throwable { }

	public void closeServer() {
		mEnabled = false;
		if (!mDatagramSocketServer.isClosed()) {
			mDatagramSocketServer.close();
		}
	}
}
