package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.IFMRecorder;
import com.vlad805.fmradio.service.recording.PcmRecorderSession;

/**
 * vlad805 (c) 2019
 */
public class LightAudioService extends AudioService implements IAudioRecordable {
	private static final String TAG = "LAS";
	private Thread mThread;

	private AudioTrack mAudioTrack;
	private AudioRecord mAudioRecorder;

	/** Session retaining legacy PCM and forwarding it to the active recorder. */
	private volatile PcmRecorderSession mPcmSession;

	/** Whether PCM history is currently retained. */
	private boolean mPreRollEnabled;

	/** Preference value deferred until the active recording is stopped. */
	private Boolean mPendingPreRollEnabled;

	private boolean mIsActive = false;

	public LightAudioService(final Context context) {
		super(context);
		mPreRollEnabled = Storage.getPrefBoolean(
				context,
				C.PrefKey.RECORDING_SAVE_PAST,
				C.PrefDefaultValue.RECORDING_SAVE_PAST
		);
		mPcmSession = createPcmSession(mPreRollEnabled);
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
		stopRecord();

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

		if (bufferSize <= 0) {
			Log.e(TAG, "Invalid AudioTrack bufferSize=" + bufferSize);
			closeAll();
			return;
		}

		mAudioTrack = new AudioTrack(
				AudioManager.STREAM_MUSIC,
				mSampleRate,
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_16BIT,
				bufferSize,
				AudioTrack.MODE_STREAM
		);

		if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
			Log.e(TAG, "AudioTrack init failed, state=" + mAudioTrack.getState());
			closeAll();
			return;
		}

		mAudioRecorder = getAudioRecorder();
		if (mAudioRecorder == null) {
			Log.e(TAG, "AudioRecord init failed: recorder is null");
			closeAll();
			return;
		}

		if (mAudioRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
			Log.e(TAG, "AudioRecord init failed, state=" + mAudioRecorder.getState());
			closeAll();
			return;
		}

		try {
			mAudioRecorder.startRecording();
		} catch (Throwable t) {
			Log.e(TAG, "AudioRecord.startRecording failed", t);
			closeAll();
			return;
		}
		mAudioTrack.play();

		int bytes;
		final short[] buffer = new short[bufferSize];

		while (mIsActive) {
			bytes = mAudioRecorder.read(buffer, 0, bufferSize);
			if (bytes <= 0) {
				Log.w(TAG, "AudioRecord.read returned " + bytes);
				continue;
			}

			if (mIsActive) {
				mPcmSession.append(buffer, bytes);
				mAudioTrack.write(buffer, 0, bytes);
			}
		}
	};

	@Override
	public void startRecord(final IFMRecorder recorder) throws RecordError {
		mPcmSession.start(recorder);
	}

	@Override
	public void stopRecord() {
		mPcmSession.stop();
		if (mPendingPreRollEnabled != null) {
			applyPreRollEnabled(mPendingPreRollEnabled);
			mPendingPreRollEnabled = null;
		}
	}

	@Override
	public synchronized void setPreRollEnabled(final boolean enabled) {
		if (mPcmSession.isRecording()) {
			mPendingPreRollEnabled = enabled;
			return;
		}
		applyPreRollEnabled(enabled);
	}

	/** Applies the preference by replacing the inactive PCM history buffer. */
	private void applyPreRollEnabled(final boolean enabled) {
		mPreRollEnabled = enabled;
		mPcmSession = createPcmSession(enabled);
	}

	/**
	 * Creates a PCM session matching the current legacy capture format.
	 *
	 * @param enabled Whether the session should retain pre-roll samples
	 * @return Configured PCM session
	 */
	private PcmRecorderSession createPcmSession(final boolean enabled) {
		return new PcmRecorderSession(
				mSampleRate,
				2,
				enabled ? C.Config.RECORDING_PRE_ROLL_SECONDS : 0
		);
	}
}
