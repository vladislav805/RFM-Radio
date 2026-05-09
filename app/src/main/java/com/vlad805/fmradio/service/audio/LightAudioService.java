package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.IFMRecorder;

/**
 * vlad805 (c) 2019
 */
public class LightAudioService extends AudioService implements IAudioRecordable {
	private static final String TAG = "LAS";
	private final PcmBridge mBridge = new PcmBridge("FmLegacyBridge");
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
		startBridge();
	}

	@Override
	public void stopAudio() {
		if (!mIsActive) {
			return;
		}

		mIsActive = false;
		mBridge.stop();
	}

	private void startBridge() {
		final int bufferSize = AudioTrack.getMinBufferSize(
				mSampleRate,
				AudioFormat.CHANNEL_IN_STEREO,
				AudioFormat.ENCODING_PCM_16BIT
		);

		if (bufferSize <= 0) {
			Log.e(TAG, "Invalid AudioTrack bufferSize=" + bufferSize);
			mIsActive = false;
			return;
		}

		final AudioTrack audioTrack = new AudioTrack(
				AudioManager.STREAM_MUSIC,
				mSampleRate,
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize,
				AudioTrack.MODE_STREAM
		);

		if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
			Log.e(TAG, "AudioTrack init failed, state=" + audioTrack.getState());
			audioTrack.release();
			mIsActive = false;
			return;
		}

		final AudioRecord audioRecorder = getAudioRecorder();
		if (audioRecorder == null) {
			Log.e(TAG, "AudioRecord init failed: recorder is null");
			audioTrack.release();
			mIsActive = false;
			return;
		}

		if (audioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
			Log.e(TAG, "AudioRecord init failed, state=" + audioRecorder.getState());
			audioRecorder.release();
			audioTrack.release();
			mIsActive = false;
			return;
		}

		final boolean started = mBridge.start(audioRecorder, audioTrack, bufferSize, (buffer, length) -> {
			if (mRecorder != null) {
				mRecorder.record(buffer, length);
			}
		});
		if (!started) {
			mIsActive = false;
		}
	}

	@Override
	public void startRecord(final IFMRecorder recorder) throws RecordError {
		mRecorder = recorder;
		recorder.startRecord();
	}

	@Override
	public void stopRecord() {
		if (mRecorder != null) {
			mRecorder.stopRecord();
		}
		mRecorder = null;
	}
}
