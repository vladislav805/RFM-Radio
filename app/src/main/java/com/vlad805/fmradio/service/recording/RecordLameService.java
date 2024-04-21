package com.vlad805.fmradio.service.recording;

import android.content.Context;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

/**
 * vlad805 (c) 2021
 */
public class RecordLameService extends RecordService implements IFMRecorder {
	private AndroidLame mLame;

	/**
	 * @param context Context
	 * @param kHz Current frequency in kHz
	 */
	public RecordLameService(final Context context, final int kHz) {
		super(context, kHz);
	}

	@Override
	protected String getExtension() {
		return "mp3";
	}

	/**
	 * When recording is being started, need create instance of encoder lame
	 */
	@Override
	protected void onFileCreated() {
		mLame = new LameBuilder()
				.setInSampleRate(44100)
				.setOutChannels(2)
				.setOutBitrate(192)
				.setOutSampleRate(44100)
				.setMode(LameBuilder.Mode.JSTEREO)
				.setQuality(8)
				.build();
	}

	/**
	 * When data is received, encode chunk of data with lame
	 * @param data New data
	 * @param length Length of data
	 * @param encoded Pointer to byte array of encoded data (output)
	 * @return Length of encoded data
	 */
	@Override
	protected int onReceivedData(short[] data, int length, byte[] encoded) {
		return mLame.encodeBufferInterLeaved(data, length / 2, encoded);
	}

	/**
	 * When recording is finished, close encoder
	 */
	@Override
	protected void onFinishRecording() {
		try {
			/**
			 * @see {@link https://github.com/vladislav805/RFM-Radio/issues/82}
			 */
			Thread.sleep(500);
			mLame.close();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}
}
