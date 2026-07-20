package com.vlad805.fmradio.service.recording;

import com.vlad805.fmradio.service.fm.RecordError;

/**
 * vlad805 (c) 2020
 */
public interface IFMRecorder {
	/**
	 * Starts a recording file.
	 *
	 * @param initialDurationMillis Duration of PCM already buffered before the request
	 * @throws RecordError If the recording target cannot be created
	 */
	void startRecord(final int initialDurationMillis) throws RecordError;

	/**
	 * Encodes and writes PCM samples.
	 *
	 * @param data PCM sample buffer
	 * @param length Number of valid samples
	 */
	void record(final short[] data, final int length);

	/** Finishes and closes the recording file. */
	void stopRecord();
}
