package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.service.fm.RecordError;

/**
 * vlad805 (c) 2020
 */
public interface IFMRecorder {
	void startRecord() throws RecordError;
	void record(final short[] data, final int length);
	void stopRecord();
}
