package com.vlad805.fmradio.service;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;

/**
 * vlad805 (c) 2019
 */
@SuppressWarnings("deprecation")
public class LightAudioService extends FMAudioService {

	private Thread mThread;

	private AudioTrack mAudioTrack;
	private AudioRecord mAudioRecorder;

	private boolean mIsActive = false;

	public LightAudioService(Context context) {
		super(context);
	}

	@Override
	public void startAudio() {
		mIsActive = true;
		mThread = new Thread(mReadWrite);
		mThread.start();
	}

	@Override
	public void stopAudio() {
		mIsActive = false;
		if (mThread != null) {
			mThread.interrupt();
		}
		closeAll();
	}

	private void closeAll() {
		if (mAudioTrack != null) {
			mAudioTrack.release();
			mAudioTrack = null;
		}

		if (mAudioRecorder != null) {
			if (mAudioRecorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				mAudioRecorder.stop();
			}
			mAudioRecorder.release();
			mAudioRecorder = null;
		}
	}

	private Runnable mReadWrite = () -> {

		int bufferSizeInBytes = AudioTrack.getMinBufferSize(
				mSampleRate,
				AudioFormat.CHANNEL_IN_STEREO,
				AudioFormat.ENCODING_PCM_16BIT
		);

		mAudioTrack = new AudioTrack(
				AudioManager.STREAM_MUSIC,
				mSampleRate,
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSizeInBytes,
				AudioTrack.MODE_STREAM
		);

		mAudioRecorder = getAudioRecorder();
		mAudioRecorder.startRecording();
		mAudioTrack.play();

		int bytes;
		byte[] buffer = new byte[bufferSizeInBytes];

		while (mIsActive) {
			bytes = mAudioRecorder.read(buffer, 0, bufferSizeInBytes);
			mAudioTrack.write(buffer, 0, bytes);
		}

		closeAll();
	};
}
