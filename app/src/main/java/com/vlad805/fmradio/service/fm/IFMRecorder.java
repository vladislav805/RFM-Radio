package com.vlad805.fmradio.service.fm;

/**
 * vlad805 (c) 2020
 */
public interface IFMRecorder {
	void startRecord() throws RecordError;
	void record(final byte[] data, final int length);
	void stopRecord();
}
