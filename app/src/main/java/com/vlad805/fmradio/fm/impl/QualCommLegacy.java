package com.vlad805.fmradio.fm.impl;

import android.content.Context;
import android.util.Log;
import com.vlad805.fmradio.BuildConfig;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.enums.MuteState;
import com.vlad805.fmradio.fm.FMController;
import com.vlad805.fmradio.fm.FMEventCallback;
import com.vlad805.fmradio.fm.IFMEventListener;
import com.vlad805.fmradio.fm.LaunchConfig;
import com.vlad805.fmradio.service.FMEventListenerServer;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

/**
 * vlad805 (c) 2020
 */
public class QualCommLegacy extends FMController implements IFMEventListener {

	public static class Config extends LaunchConfig {
		@Override
		public int getClientPort() {
			return 2112;
		}

		@Override
		public int getServerPort() {
			return 2113;
		}
	}

	private FMEventListenerServer mServer;

	private FMEventCallback mEventCallback;

	public QualCommLegacy(final LaunchConfig config, final Context context) {
		super(config, context);
		mQueueCommands = new LinkedList<>();
	}

	public void setEventListener(final FMEventCallback callback) {
		mEventCallback = callback;
	}

	/**
	 * Returns filename of binary by architecture
	 * @return Filename of binary
	 */
	private String getBinaryName() {
		return "fmbin-" + Utils.determineArch();
	}

	private String getBinaryPath() {
		return getAppRootPath(getBinaryName());
	}

	@Override
	public boolean isInstalled() {
		return new File(getBinaryPath()).exists();
	}

	@Override
	public boolean isObsolete() {
		return BuildConfig.DEBUG;
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	@Override
	protected boolean installImpl() {
		File dir = new File(getAppRootPath());
		if (!dir.exists()) {
			dir.mkdirs();
		}

		try {
			// Copy binary
			boolean success = copyBinary();

			// Unsuccessfully
			if (!success) {
				throw new Error("Error while copy binary");
			}
		} catch (FileNotFoundException e) {
			// FileNotFoundException - Text file busy
			// Throws when binary is launched
			Utils.shell("killall " + getBinaryPath(), true);

			try {
				copyBinary();
			} catch (FileNotFoundException e2) {
				throw new Error("Error while copy binary after kill busy binary");
			}
		}

		int ret = Utils.shell("chmod 777 " + getBinaryPath() + " 1>/dev/null 2>/dev/null", true);

		return ret == 0;
	}

	/**
	 * Copy binary from assets
	 * @return True if success
	 */
	private boolean copyBinary() throws FileNotFoundException {
		return copyAsset(context, getBinaryName(), getBinaryPath());
	}

	@Override
	protected boolean launchImpl() {
		String command = String.format("%s 1>/dev/null 2>/dev/null &", getBinaryPath());
		Utils.shell(command, true);
		startServerListener();
		sendCommand("init");
		return true; // TODO REAL STATE
	}

	@Override
	protected boolean killImpl() {
		mServer.closeServer();
		String command = String.format("killall %1$s 1>/dev/null 2>/dev/null &", getBinaryName());
		return Utils.shell(command, true) == 0;
	}

	@Override
	protected boolean enableImpl() {
		sendCommand("enable");
		return true; // TODO REAL STATE
	}

	@Override
	protected boolean disableImpl() {
		sendCommand("disable");
		return true; // TODO REAL STATE
	}

	@Override
	protected boolean setFrequencyImpl(int kHz) {
		sendCommand("setfreq " + kHz);
		return true; // TODO REAL STATE
	}

	@Override
	protected void getSignalStretchImpl(final Callback<Integer> result) {
		result.onResult(0); // TODO
	}

	@Override
	protected void jumpImpl(final int direction, final Callback<Integer> callback) {
		final Request req = new Request("jump " + direction);
		req.setListener(data -> {
			/*
			 * TODO in response need replace "ok" by frequency
			 * https://github.com/vladislav805/RFM-Radio/issues/32
			 */
			callback.onResult(0);

		});
		sendCommand(req);
	}

	@Override
	protected void hwSeekImpl(final int direction, final Callback<Integer> callback) {
		final Request req = new Request("seekhw " + direction);
		req.setListener(data -> {
			/*
			 * TODO in response need replace "ok" by frequency
			 * https://github.com/vladislav805/RFM-Radio/issues/32
 			 */
			callback.onResult(0);

		});
		sendCommand(req);
	}

	@Override
	public void setMute(MuteState state) {

	}

	@Override
	public void search() {

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

		public void setTimeout(int timeout) {
			this.timeout = timeout;
		}

		public void setListener(OnReceivedResponse listener) {
			this.listener = listener;
		}

		public byte[] bytes() {
			return command.getBytes();
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

	/**
	 * Copy file from application assets
	 * @param context Context
	 * @param fromAssetPath Path in assets
	 * @param toPath Destination path
	 * @return True on success
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private boolean copyAsset(Context context, String fromAssetPath, String toPath) throws FileNotFoundException {
		InputStream in;
		OutputStream out;
		try {
			in = context.getAssets().open(fromAssetPath);
			new File(toPath).createNewFile();
			out = new FileOutputStream(toPath);

			byte[] buffer = new byte[1024];
			int read;
			while((read = in.read(buffer)) != -1){
				out.write(buffer, 0, read);
			}

			in.close();
			out.flush();
			out.close();
			return true;
		} catch (IOException e) {
			if (e instanceof FileNotFoundException) {
				throw (FileNotFoundException) e;
			}
			e.printStackTrace();
			return false;
		}
	}

	private DatagramSocket mDatagramSocketClient;

	private void initSocket() throws IOException {
		mDatagramSocketClient = new DatagramSocket(0);
		Log.i("QCL", "Created socket");

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
		final Request command = mQueueCommands.peek();

		if (command == null) {
			return;
		}

		new Thread(() -> {
			try {
				if (mDatagramSocketClient == null) {
					initSocket();
				}

				// mDatagramSocketClient.setSoTimeout(timeout);
				mDatagramSocketClient.setSoTimeout(5000);

				Log.d("QCL", "Sent command: " + command);

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

				Log.d("QCL", "Received response: " + res);

				command.fire(res);

				mQueueCommands.remove();
				sendCommandReal();
			} catch (Throwable e) {
				e.printStackTrace();
				Log.i("QCL", "FAILED: attempt for request [" + command + "]");
			}
		}).start();
	}

	private void startServerListener() {
		try {
			mServer = new FMEventListenerServer(config.getServerPort());
			mServer.setCallback(mEventCallback);
			mServer.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
