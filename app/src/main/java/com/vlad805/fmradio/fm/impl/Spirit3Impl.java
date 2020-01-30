package com.vlad805.fmradio.fm.impl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.fm.FMController;
import com.vlad805.fmradio.fm.IFMEventPoller;
import com.vlad805.fmradio.fm.LaunchConfig;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

/**
 * vlad805 (c) 2020
 */
public class Spirit3Impl extends FMController implements IFMEventPoller {

	public static class Config extends LaunchConfig {
		@Override
		public int getClientPort() {
			return 2122;
		}
	}

	public Spirit3Impl(final LaunchConfig config, final Context context) {
		super(config, context);
		mQueueCommands = new LinkedList<>();
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
	protected boolean installImpl() {
		return true;
	}

	@Override
	protected boolean launchImpl() {
		String command = String.format("%s 4 1>/dev/null 2>/dev/null &", getBinaryPath());
		return Utils.shell(command, true) == 0;
	}

	@Override
	protected boolean killImpl() {
		String command = String.format("killall %s 1>/dev/null 2>/dev/null &", getBinaryName());
		return Utils.shell(command, true) == 0;
	}

	@Override
	protected boolean enableImpl() {
		try {
			sendCommandSync(new Request("s radio_nop start").setTimeout(100));
			sendCommandSync(new Request("s tuner_state start").setTimeout(5000));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	protected boolean disableImpl() {
		try {
			sendCommandSync(new Request("s tuner_state stop").setTimeout(5000));
			return true;
		} catch (IOException e) {
			return true;
		}
	}

	@Override
	protected boolean setFrequencyImpl(final int kHz) {
		try {
			sendCommandSync(new Request("s tuner_freq " + kHz));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public void getSignalStretchImpl(final Callback<Integer> result) {
		result.onResult(0); // TODO
	}

	private String toDirection(int direction) {
		return direction > 0 ? "up" : "down";
	}

	@Override
	public void jumpImpl(final int direction, final Callback<Integer> callback) {
		try {
			String res = sendCommandSync(new Request("g tuner_freq"));
			int frequency = Utils.parseInt(res);
			frequency += direction > 0 ? 100 : -100;
			setFrequency(frequency);
			callback.onResult(frequency);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
		try {
			String res = sendCommandSync(new Request("s tuner_scan_state " + toDirection(direction)).setTimeout(15000));

			callback.onResult(Utils.parseInt(res));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void setMute(MuteState state) {

	}

	@Override
	public void search() {

	}

	private static Request cmdTunerFreq = new Request("g tuner_freq");
	private static Request cmdTunerRssi = new Request("g tuner_rssi");
	private static Request cmdRdsPs = new Request("g rds_ps");

	@Override
	public Bundle poll() {
		Bundle bundle = new Bundle();
		String tmp;

		try {
			tmp = sendCommandSync(cmdTunerFreq);
			if (tmp != null) {
				int frequency = Utils.parseInt(tmp);
				bundle.putInt(C.Key.FREQUENCY, frequency);
			}

			tmp = sendCommandSync(cmdTunerRssi);
			if (tmp != null) {
				int rssi = Utils.parseInt(tmp);
				bundle.putInt(C.Key.RSSI, rssi);
			}

			tmp = sendCommandSync(cmdRdsPs);
			if (tmp != null) {
				bundle.putString(C.Key.PS, tmp);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		return bundle;
	}

	interface OnReceivedResponse {
		void onResponse(String data);
	}

	static class Request {
		private final String command;
		private int timeout = 1000;
		private OnReceivedResponse listener;

		public Request(String cmd) {
			command = cmd;
		}

		public Request setTimeout(int timeout) {
			this.timeout = timeout;
			return this;
		}

		public Request setListener(OnReceivedResponse listener) {
			this.listener = listener;
			return this;
		}

		public byte[] bytes() {
			return command.getBytes();
		}

		public String getCommand() {
			return command;
		}

		public int size() {
			return command.length();
		}

		public int getTimeout() {
			return timeout;
		}

		public void fire(String result) {
			if (listener != null) {
				listener.onResponse(result);
			}
		}
	}

	private DatagramSocket mDatagramSocketClient;

	private void initSocket() throws IOException {
		mDatagramSocketClient = new DatagramSocket(0);
		Log.i("S3I", "Created socket");

	}

	private Queue<Request> mQueueCommands;

	private void sendCommand(String command) {
		sendCommand(new Request(command));
	}

	private void sendCommand(Request command) {
		mQueueCommands.offer(command);

		if (mQueueCommands.size() == 1) {
			sendCommandReal();
		}
	}

	private static InetAddress LOOPBACK;

	static {
		try {
			LOOPBACK = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	private void sendCommandReal() {
		Request command = mQueueCommands.peek();

		if (command == null) {
			return;
		}

		new Thread(() -> {
			try {
				String res = sendDataSocketAndWaitForResult(command);

				Log.d("S3I", "Received response: " + res);

				command.fire(res);

				mQueueCommands.remove();
				sendCommandReal();
			} catch (Throwable e) {
				e.printStackTrace();
				Log.i("S3I", "FAILED: attempt for request [" + command + "]");
			}
		}).start();
	}

	private String sendDataSocketAndWaitForResult(Request command) throws IOException {
		if (mDatagramSocketClient == null) {
			initSocket();
		}

		mDatagramSocketClient.setSoTimeout(command.getTimeout());

		Log.d("S3I", "Sent command: " + command.getCommand());

		DatagramPacket dps = new DatagramPacket(command.bytes(), command.size(), LOOPBACK, config.getClientPort());

		mDatagramSocketClient.send(dps);

		byte[] buf = new byte[40];
		dps.setData(buf);
		mDatagramSocketClient.receive(dps);

		int size = dps.getLength();

		if (size < 0) {
			System.out.println("read: size read -1");
			return null;
		}

		return new String(buf, 0, size, StandardCharsets.UTF_8);
	}

	private String sendCommandSync(Request command) throws IOException {
		return sendDataSocketAndWaitForResult(command);
	}

}
