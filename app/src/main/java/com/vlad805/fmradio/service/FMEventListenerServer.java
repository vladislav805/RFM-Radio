package com.vlad805.fmradio.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.vlad805.fmradio.C;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

/**
 * vlad805 (c) 2019
 */
public class FMEventListenerServer extends Thread {

	private DatagramSocket mDatagramSocketServer;
	private Context mContext;

	private static final int BUFFER_SIZE = 96; // 2 + 1 + 4 * 20 + 1 = 84 as minimum

	private boolean mEnabled = false;

	private static final int EVT_ENABLED = 1;
	private static final int EVT_DISABLED = 2;

	private static final int EVT_FREQUENCY_SET = 4;
	private static final int EVT_UPDATE_RSSI = 5;
	private static final int EVT_UPDATE_PS = 6;
	private static final int EVT_UPDATE_RT = 7;
	private static final int EVT_SEEK_COMPLETE = 8;
	private static final int EVT_STEREO = 9; // РАБОТАЕТ 0 и 1
	private static final int EVT_SEARCH_DONE = 10;


	public FMEventListenerServer(Context context, int port) throws IOException {
		Log.i("FMELS", "init FMELS");
		mDatagramSocketServer = new DatagramSocket(port);

		mContext = context;
	}

	@Override
	public void run() {
		Log.i("FMELS", "run FMELS");
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

	private String handle(String data) throws StopServer {
		String[] lines = data.split("\n");

		if (lines.length == 0) {
			return null;
		}

		int evt;
		try {
			evt = Integer.valueOf(lines[0]);
		} catch (NumberFormatException e) {
			e.printStackTrace();
			return null;
		}

		StringBuilder sb = new StringBuilder();

		for (String line : lines) {
			sb.append(line).append(" / ");
		}


		Log.i("FMELS", "received new event = [" + sb.toString() + "]");

		Intent intent = new Intent();

		switch (evt) {
			case EVT_ENABLED:
				intent.setAction(C.Event.ENABLED);
				break;

			case EVT_DISABLED:
				intent.setAction(C.Event.DISABLED);
				throw new StopServer();

			case EVT_FREQUENCY_SET:
				if (lines.length < 2) {
					break;
				}
				intent.setAction(C.Event.FREQUENCY_SET);
				intent.putExtra(C.Key.FREQUENCY, Integer.valueOf(lines[1]));
				break;

			case EVT_UPDATE_RSSI:
				intent.setAction(C.Event.UPDATE_RSSI);
				intent.putExtra(C.Key.RSSI, Integer.valueOf(lines[1]));
				break;

			case EVT_UPDATE_PS:
				if (lines.length < 2) {
					break;
				}
				intent.setAction(C.Event.UPDATE_PS);
				intent.putExtra(C.Key.PS, lines[1]);
				break;

			case EVT_SEARCH_DONE:
				if (lines.length < 2) {
					break;
				}
				String stations = lines[1].trim();
				int lengthKHz = 4;
				int count = stations.length() / lengthKHz;

				int[] res = new int[count];

				for (int i = 0; i < count; ++i) {
					int start = i * lengthKHz;
					res[i] = Integer.valueOf(stations.substring(start, start + lengthKHz)) * 100;
				}

				Arrays.sort(res);

				intent.setAction(C.Event.SEARCH_DONE);
				intent.putExtra(C.Key.STATION_LIST, res);
				break;

			default:
				Log.e("FMELS", "unknown event = " + evt);
				return null;
		}

		mContext.sendBroadcast(intent);

		return "ok";
	}

	private class StopServer extends Throwable { }
}
