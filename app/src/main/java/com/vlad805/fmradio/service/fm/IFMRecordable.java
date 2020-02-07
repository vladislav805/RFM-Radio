package com.vlad805.fmradio.service.fm;

/**
 * vlad805 (c) 2020
 */
public interface IFMRecordable {
	void newRecord(final FMController.Callback<IFMRecorder> callback);
}
