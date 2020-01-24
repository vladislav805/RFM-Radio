package com.vlad805.fmradio.fm.impl;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.fm.IFMController;
import com.vlad805.fmradio.fm.IRdsStruct;
import com.vlad805.fmradio.fm.LaunchConfig;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

/**
 * vlad805 (c) 2020
 */
public class Spirit3Impl extends IFMController {

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
	public void jump(int direction) {
		sendCommand("s tuner_freq " + toDirection(direction));
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
	public Intent poll() {

		return null;
	}

	@Override
	public IRdsStruct getRds() {
		return null;
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

		public void setListener(OnReceivedResponse listener) {
			this.listener = listener;
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

	private void sendCommandReal() {
		Request command = mQueueCommands.peek();

		if (command == null) {
			return;
		}

		new Thread(() -> {
			try {
				if (mDatagramSocketClient == null) {
					initSocket();
				}

				mDatagramSocketClient.setSoTimeout(command.getTimeout());

				Log.d("S3I", "Sent command: " + command.getCommand());

				DatagramPacket dps = new DatagramPacket(command.bytes(), command.size(), InetAddress.getByName("127.0.0.1"), config.getClientPort());

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

				Log.d("S3I", "Received response: " + res);

				mQueueCommands.remove();
				sendCommandReal();
			} catch (Throwable e) {
				e.printStackTrace();
				Log.i("S3I", "FAILED: attempt for request [" + command + "]");
			}
		}).start();
	}

}
