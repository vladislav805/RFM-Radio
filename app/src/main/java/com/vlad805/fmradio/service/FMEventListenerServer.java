package com.vlad805.fmradio.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.vlad805.fmradio.C;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

import static com.vlad805.fmradio.Utils.parseInt;

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
	private static final int EVT_STEREO = 9;
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

	private String mLastRT = "";
	private int mNextAction = 0;

	private static final int RT_ACTION_NONE = 0;
	private static final int RT_ACTION_CLEAR = 1;

	private final char FF = 0x0c; // split for messages
	private final char LF = 0x0a; // continue
	private final char CR = 0x0d; // clear

	private String handle(String data) throws StopServer {
		int splitOn = data.indexOf(FF);
		String code = data.substring(0, splitOn);
		data = data.substring(splitOn + 1);


		int evt = parseInt(code);

		Log.i("FMELS", "received new event " + code + " = [" + data + "]");

		Intent intent = new Intent();

		switch (evt) {
			case EVT_ENABLED:
				intent.setAction(C.Event.ENABLED);
				break;

			case EVT_DISABLED:
				intent.setAction(C.Event.DISABLED);
				throw new StopServer();

			case EVT_FREQUENCY_SET:
				intent.setAction(C.Event.FREQUENCY_SET);
				intent.putExtra(C.Key.FREQUENCY, Integer.valueOf(data));
				break;

			case EVT_UPDATE_RSSI:
				intent.setAction(C.Event.UPDATE_RSSI);
				intent.putExtra(C.Key.RSSI, Integer.valueOf(data));
				break;

			case EVT_UPDATE_PS:
				intent.setAction(C.Event.UPDATE_PS);
				intent.putExtra(C.Key.PS, data);
				break;

			case EVT_UPDATE_RT:
				/*if (mNextAction == RT_ACTION_CLEAR) {
					mLastRT = "";
				}

				mNextAction = data.indexOf(CR) >= 0 ? RT_ACTION_CLEAR : RT_ACTION_NONE;

				Log.e("RT", str2Hex(data));

				if (data.indexOf(CR) >= 0) {
					Log.e("FMELS", "CR is here!!!!!");
				}

				if (data.indexOf(LF) >= 0) {
					Log.e("FMELS", "LF is here!!!!!");
				}

				mLastRT += data.replace("" + CR, "");*/

				mLastRT = data;

				intent.setAction(C.Event.UPDATE_RT);
				intent.putExtra(C.Key.RT, mLastRT);
				break;

			case EVT_SEARCH_DONE:
				String stations = data.trim();
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

			case EVT_STEREO:
				String mode = data.trim();

				intent.setAction(C.Event.UPDATE_STEREO);
				intent.putExtra(C.Key.STEREO_MODE, mode.equals("1"));
				break;

			default:
				Log.e("FMELS", "unknown event = " + evt);
				return null;
		}

		mContext.sendBroadcast(intent);

		return "ok";
	}

	private class StopServer extends Throwable { }




	public static String str2Hex(String bin) {
		char[] digital = "0123456789ABCDEF".toCharArray();
		StringBuffer sb = new StringBuffer("");
		byte[] bs = bin.getBytes();
		int bit;
		for (byte b : bs) {
			bit = (b & 0x0f0) >> 4;
			sb.append(digital[bit]);
			bit = b & 0x0f;
			sb.append(digital[bit]);
		}
		return sb.toString();
	}
}
