package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.service.fm.RecordError;

/**
 * vlad805 (c) 2020
 */
public interface IAudioRecordable {
	void startRecord(final IFMRecorder driver) throws RecordError;
	void stopRecord();
}
