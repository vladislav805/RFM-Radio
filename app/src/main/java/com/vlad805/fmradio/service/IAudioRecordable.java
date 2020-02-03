package com.vlad805.fmradio.service;

import com.vlad805.fmradio.service.fm.IFMRecorder;

/**
 * vlad805 (c) 2020
 */
public interface IAudioRecordable {
	void startRecord(final IFMRecorder driver);
	void stopRecord();
}
