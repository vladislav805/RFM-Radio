package com.vlad805.fmradio.service.fm.communications;

import android.util.Log;
import com.vlad805.fmradio.service.fm.LaunchConfig;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;

/**
 * vlad805 (c) 2020
 */
public class Poll {
	private final LaunchConfig config;
	private final Queue<Request> queue;
	private DatagramSocket socket;
	private InetAddress endpoint;

	public Poll(final LaunchConfig config) {
		this.config = config;
		this.queue = new LinkedList<>();

		try {
			endpoint = InetAddress.getByName("127.0.0.1");
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}

	private void createSocket() {
		try {
			this.socket = new DatagramSocket(0);
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	public void send(final Request request) {
		queue.offer(request);

		if (queue.size() == 1) {
			next();
		}
	}

	private void next() {
		final Request command = queue.peek();

		if (command == null) {
			return;
		}

		new Thread(() -> {
			try {
				if (socket == null) {
					createSocket();
				}

				socket.setSoTimeout(command.getTimeout());

				final DatagramPacket dps = new DatagramPacket(command.bytes(), command.size(), endpoint, config.getClientPort());

				socket.send(dps);

				final byte[] buf = new byte[40];
				dps.setData(buf);

				socket.receive(dps);

				final int length = dps.getLength();

				if (length < 0) {
					System.out.println("read: size read -1");
					return;
				}

				final String res = new String(buf, 0, length - 1, StandardCharsets.UTF_8);

				command.fire(res);

				queue.remove();

				if (!queue.isEmpty()) {
					next();
				}
			} catch (Throwable e) {
				e.printStackTrace();
				Log.i("QCL", "FAILED: attempt for request [" + command + "]");
			}
		}).start();
	}


}
