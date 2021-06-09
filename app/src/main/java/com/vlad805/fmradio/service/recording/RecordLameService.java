package com.vlad805.fmradio.service.recording;

import android.content.Context;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;
import com.vlad805.fmradio.service.fm.IFMRecorder;

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

	@Override
	protected int onReceivedData(short[] data, int length, byte[] encoded) {
		return mLame.encodeBufferInterLeaved(data, length / 2, encoded);
	}

	@Override
	protected void onFinishRecording() {
		mLame.close();
	}
}
