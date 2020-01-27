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

	public Spirit3Impl(LaunchConfig config) {
		super(config);
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
	public void install(Context context) {

	}

	@Override
	public void launch(Context context) {
		String command = String.format("%s 4 1>/dev/null 2>/dev/null &", getBinaryPath());
		Utils.shell(command, true);
	}

	@Override
	public void kill() {
		String command = String.format("killall %s 1>/dev/null 2>/dev/null &", getBinaryName());
		Utils.shell(command, true);
	}

	@Override
	public void enable() {
		sendCommand(new Request("s radio_nop start").setTimeout(100));
		sendCommand(new Request("s tuner_state start").setTimeout(5000));
	}

	@Override
	public void disable() {
		sendCommand(new Request("s tuner_state stop").setTimeout(5000));
	}

	@Override
	public void setFrequency(int kHz) {
		sendCommand("s tuner_freq " + kHz);
	}

	@Override
	public int getSignalStretch() {
		return 0;
	}

	private String toDirection(int direction) {
		return direction > 0 ? "up" : "down";
	}

	@Override
	public void jump(final int direction) {
		sendCommand(new Request("g tuner_freq").setListener(data -> {
			if (data.length() == 4 || data.length() == 5) {
				int frequency = Integer.parseInt(data) * 10;

				frequency += direction > 0 ? 100 : -100;

				setFrequency(frequency);
			}
		}));
	}

	@Override
	public void hwSeek(int direction) {
		sendCommand("s tuner_scan_state " + toDirection(direction));
	}

	@Override
	public void setMute(MuteState state) {

	}

	@Override
	public void search() {

	}

	@Override
	public Bundle poll() {
		Bundle bundle = new Bundle();

		String f = sendCommandSync(new Request("g tuner_freq"));
		if (f != null) {
			int frequency = Integer.parseInt(f);
			bundle.putInt(C.Key.FREQUENCY, frequency);
		}

		String r = sendCommandSync(new Request("g tuner_rssi"));
		if (r != null) {
			int rssi = Integer.parseInt(r);
			bundle.putInt(C.Key.RSSI, rssi);
		}

		String p = sendCommandSync(new Request("g rds_ps"));
		if (p != null) {
			bundle.putString(C.Key.PS, p);
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

	private String sendCommandSync(Request command) {
		try {
			return sendDataSocketAndWaitForResult(command);
		} catch (Throwable e) {
			e.printStackTrace();
			Log.i("S3I", "FAILED: attempt for request [" + command + "]");
			return null;
		}
	}

}
