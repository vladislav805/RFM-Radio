package com.vlad805.fmradio.service.fm.communication;

/**
 * vlad805 (c) 2020
 */
public class Request {
	private final String command;
	private final int timeout;
	private OnReceivedResponse listener;
	private OnRequestError errorListener;

	public interface OnReceivedResponse {
		void onResponse(final String data);
	}

	public interface OnRequestError {
		void onError(final Throwable error);
	}

	public Request(final String command, final int timeout) {
		this.command = command;
		this.timeout = timeout;
	}

	public Request(final String command) {
		this(command, 1000);
	}

	public Request onResponse(OnReceivedResponse listener) {
		this.listener = listener;
		return this;
	}

	public Request onError(final OnRequestError listener) {
		this.errorListener = listener;
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

	public void fire(final String result) {
		if (listener != null) {
			listener.onResponse(result);
		}
	}

	public void fireError(final Throwable error) {
		if (errorListener != null) {
			errorListener.onError(error);
		}
	}
}
