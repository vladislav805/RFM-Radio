package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.IFMRecorder;

/**
 * vlad805 (c) 2019
 */
@SuppressWarnings("deprecation")
public class LightAudioService extends AudioService implements IAudioRecordable {
	private static final String TAG = "LAS";
	private Thread mThread;

	private AudioTrack mAudioTrack;
	private AudioRecord mAudioRecorder;
	private IFMRecorder mRecorder;

	private boolean mIsActive = false;

	public LightAudioService(final Context context) {
		super(context);
	}

	@Override
	public void startAudio() {
		if (mIsActive) {
			return;
		}

		mIsActive = true;
		mThread = new Thread(mReadWrite);
		mThread.start();
	}

	@Override
	public void stopAudio() {
		if (!mIsActive) {
			return;
		}

		mIsActive = false;
		if (mThread != null) {
			mThread.interrupt();
		}
		closeAll();
	}

	private void closeAll() {
		mIsActive = false;
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

	private final Runnable mReadWrite = () -> {
		final int bufferSize = AudioTrack.getMinBufferSize(
				mSampleRate,
				AudioFormat.CHANNEL_IN_STEREO,
				AudioFormat.ENCODING_PCM_16BIT
		);

		mAudioTrack = new AudioTrack(
				AudioManager.STREAM_MUSIC,
				mSampleRate,
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize,
				AudioTrack.MODE_STREAM
		);

		mAudioRecorder = getAudioRecorder();
		mAudioRecorder.startRecording();
		mAudioTrack.play();

		int bytes;
		final short[] buffer = new short[bufferSize];

		while (mIsActive) {
			bytes = mAudioRecorder.read(buffer, 0, bufferSize);

			if (mIsActive) {
				mAudioTrack.write(buffer, 0, bytes);

				// If recording enabled, write to recorder
				if (mRecorder != null) {
					mRecorder.record(buffer, bytes);
				}
			}
		}
	};

	@Override
	public void startRecord(final IFMRecorder recorder) throws RecordError {
		mRecorder = recorder;
		recorder.startRecord();
	}

	@Override
	public void stopRecord() {
		mRecorder.stopRecord();
		mRecorder = null;
	}
}
