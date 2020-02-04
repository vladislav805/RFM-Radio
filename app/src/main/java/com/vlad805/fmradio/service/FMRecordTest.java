package com.vlad805.fmradio.service;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import com.vlad805.fmradio.service.fm.IFMRecorder;

import java.io.File;
import java.io.IOException;

/**
 * vlad805 (c) 2020
 */
public class FMRecordTest implements IFMRecorder {
	private static final String TAG = "FMRT";
	private Context mContext;
	private int mKHz;

	private MediaRecorder mRecorder;

	public FMRecordTest(final Context context, final int kHz) {
		mContext = context;
		mKHz = kHz;
	}

	@Override
	public void startRecord() {
		mRecorder = new MediaRecorder();
		mRecorder.setAudioSource(1998);
		mRecorder.setAudioChannels(2);
		mRecorder.setAudioSamplingRate(44100);
		mRecorder.setOutputFormat(MediaRecorder.OutputFormat.DEFAULT);
		mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
		mRecorder.setOutputFile(Environment.getExternalStorageDirectory() + File.separator + "test.wav");

		try {
			mRecorder.prepare();
			mRecorder.start();
		} catch (IOException e) {
			Log.e(TAG, "prepare failed");
			e.printStackTrace();
		}
	}

	@Override
	public void record(byte[] data, int length) {

	}

	@Override
	public void stopRecord() {
		mRecorder.stop();
		mRecorder.release();
	}
}
