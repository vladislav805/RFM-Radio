package com.vlad805.fmradio.service;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.util.Log;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.fm.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * vlad805 (c) 2019
 */
public class FM {

	private static FM sInstance; // TODO

	private Context mContext;

	private IImplementation mImpl;

	private static final int CS_PORT_CLIENT = 2112;
	private static final int CS_PORT_SERVER = 2113;

	private DatagramSocket mDatagramSocketClient;

	public enum State {
		TURN_OFF,
		TURN_ON
	};

	private static volatile State sState = State.TURN_OFF;

	public static State getState() {
		return sState;
	}

	public static FM getInstance() {
		if (sInstance == null) {
			sInstance = new FM();
		}

		return sInstance;
	}

	private FM() {}

	public void setImpl(IImplementation implementation) {
		if (mImpl != null) {
			Log.e("FM", "Implementation already assigned");
			return;
		}

		mImpl = implementation;
	}

	public void setup(final Context context, final OnResponseReceived<Void> onReady) {
		Log.i("FM", "Setup...");
		mContext = context;

		startServerListener();

		new Thread(() -> mImpl.init(FM.this, (Void) -> mImpl.setup(onReady))).start();
	}

	public boolean copyBinary(String from, String to) {
		return copyAsset(mContext.getAssets(), from, to);
	}

	private static boolean copyAsset(AssetManager assetManager, String fromAssetPath, String toPath) {
		InputStream in;
		OutputStream out;
		try {
			in = assetManager.open(fromAssetPath);
			try {
				new File(toPath).createNewFile();
				out = new FileOutputStream(toPath);
			} catch (FileNotFoundException e) {
				String cmd = "killall " + fromAssetPath;
				Log.e("FM", "cmd = " + cmd);
				Utils.shell(cmd, true);

				new File(toPath).createNewFile();
				out = new FileOutputStream(toPath);
			}

			copyFile(in, out);
			in.close();
			out.flush();
			out.close();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	public static void copyFile(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[1024];
		int read;
		while((read = in.read(buffer)) != -1){
			out.write(buffer, 0, read);
		}
	}

	private void initSocket() throws IOException {
		mDatagramSocketClient = new DatagramSocket(0);
		Log.i("SEND", "created socket");

	}

	public void sendCommand(String request, final ICallback callback) {
		sendCommand(new DSRequest(request.getBytes(), 2000), callback);
	}

	/**
	 * Отправка команды нативной программе через сокеты
	 */
	public void sendCommand(final DSRequest request, final ICallback callback) {
		//Log.i("FM", "sendCommand");
		new Thread(() -> {
			try {
				if (mDatagramSocketClient == null) {
					initSocket();
				}

				mDatagramSocketClient.setSoTimeout(request.getTimeout());

				Log.d("sendCmd >>>", request.toString());

				DatagramPacket dps = new DatagramPacket(request.getData(), request.getLength(), InetAddress.getByName("127.0.0.1"), CS_PORT_CLIENT);

				mDatagramSocketClient.send(dps);

				byte[] buf = new byte[40];
				dps.setData(buf);
				mDatagramSocketClient.receive(dps);

				int size = dps.getLength();

				if (size < 0) {
					System.out.println("read: size read -1");
					return;
				}

				String res = new String(buf, 0, size - 1, StandardCharsets.UTF_8);

				if (callback != null) {
					callback.onResult(res);
				}
			} catch (Throwable e) {
				e.printStackTrace();
				Log.i("FM.send", "attempt for request [" + new String(request.getData()) + "]");
			}
		}).start();
	}

	public static void send(Context context, String action, String... args) {
		if ((args.length % 2) != 0) {
			throw new RuntimeException("Arguments count invalid");
		}

		Intent intent = new Intent(context, FMService.class);
		intent.setAction(action);

		for (int i = 0; i < args.length; i += 2) {
			intent.putExtra(args[i], args[i + 1]);
		}

		context.startService(intent);
	}

	private void startServerListener() {
		try {
			new FMEventListenerServer(mContext, CS_PORT_SERVER).start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void enable(OnResponseReceived<Void> listener) {
		mImpl.enable(this, listener);
		sState = State.TURN_ON;
	}

	public void disable(OnResponseReceived<Void> listener) {
		mImpl.disable(this, listener);
		sState = State.TURN_OFF;
	}

	public void setFrequency(int kHz, OnResponseReceived<Void> listener) {
		mImpl.setFrequency(this, kHz, listener);
	}

	public void getRssi(OnResponseReceived<Integer> listener) {
		mImpl.getRssi(this, listener);
	}

	public void hardwareSeek(int direction, OnResponseReceived<Integer> listener) {
		if (direction < 0 || direction > 1) {
			return;
		}

		mImpl.hardwareSeek(this, direction, listener);
	}

	public void jump(int direction, OnResponseReceived<Void> listener) {
		mImpl.jump(this, direction, listener);
	}

	public void setMute(MuteState state, OnResponseReceived<Void> listener) {
		mImpl.setMute(this, state, listener);
	}

	public void search(OnResponseReceived<List<Integer>> listener) {
		mImpl.search(this, listener);
	}

	public void kill(OnResponseReceived<Void> listener) {
		mImpl.kill(this, listener);
	}
}
