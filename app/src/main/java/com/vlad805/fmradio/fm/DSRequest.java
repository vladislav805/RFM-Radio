package com.vlad805.fmradio.fm;

/**
 * vlad805 (c) 2019
 */
public class DSRequest {

	private static final int TIMEOUT_DEFAULT = 100;

	private byte[] mData;

	private int mTimeout;

	public DSRequest(byte[] data) {
		this(data, TIMEOUT_DEFAULT);
	}

	public DSRequest(byte[] data, int timeout) {
		mData = data;
		mTimeout = timeout;
	}

	public DSRequest(String data) {
		this(data.getBytes());
	}

	public DSRequest(String data, int timeout) {
		this(data.getBytes(), timeout);
	}

	public byte[] getData() {
		return mData;
	}

	public int getLength() {
		return mData.length;
	}

	public int getTimeout() {
		return mTimeout;
	}

	@Override
	public String toString() {
		return new String(mData);
	}
}