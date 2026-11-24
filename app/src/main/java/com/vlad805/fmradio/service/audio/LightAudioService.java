package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;
import com.vlad805.fmradio.helper.Audio;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IFMRecorder;
import com.vlad805.fmradio.service.recording.PcmFormat;

/**
 * vlad805 (c) 2019
 */
public class LightAudioService extends AudioService {
	private static final String TAG = "LAS";
	private Thread mThread;

	private AudioTrack mAudioTrack;
	private AudioRecord mAudioRecorder;

	private boolean mIsActive = false;
	private final boolean mUseForceUse;
	private final Runnable mRefreshAudioRoute;
	private final QualcommRoutedAudio.ForceUseSink mForceUseSink;
	private AudioOutput mOutput = AudioOutput.HEADPHONES;

	public LightAudioService(final Context context) {
		this(context, true, () -> {});
	}

	public LightAudioService(
			final Context context,
			final boolean useForceUse,
			final Runnable refreshAudioRoute
	) {
		super(context);
		mUseForceUse = useForceUse;
		mRefreshAudioRoute = refreshAudioRoute != null ? refreshAudioRoute : () -> {};
		mForceUseSink = new QualcommRoutedAudio.ForceUseSink() {
			@Override
			public boolean isForceSpeakerNow() {
				return Audio.isForceSpeakerNow();
			}

			@Override
			public void setForceSpeaker(final boolean enabled) {
				Audio.toggleThroughSpeaker(enabled);
			}
		};
	}

	LightAudioService(
			final boolean useForceUse,
			final Runnable refreshAudioRoute,
			final QualcommRoutedAudio.ForceUseSink forceUseSink
	) {
		super(null, false);
		mUseForceUse = useForceUse;
		mRefreshAudioRoute = refreshAudioRoute != null ? refreshAudioRoute : () -> {};
		mForceUseSink = forceUseSink;
	}

	@Override
	public PcmFormat getPcmFormat() {
		return PcmFormat.PCM16_STEREO_44100;
	}

	@Override
	public AudioOutput getOutput() {
		if (mUseForceUse) {
			return mForceUseSink.isForceSpeakerNow() ? AudioOutput.SPEAKER : AudioOutput.HEADPHONES;
		}
		return mOutput;
	}

	@Override
	public void setOutput(final AudioOutput output) {
		mOutput = output;
		mRefreshAudioRoute.run();
		if (mUseForceUse) {
			mForceUseSink.setForceSpeaker(output == AudioOutput.SPEAKER);
		}
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
		mPcmSession.clearHistory();

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
		final PcmFormat pcmFormat = getPcmFormat();
		final int bufferSize = AudioTrack.getMinBufferSize(
				pcmFormat.sampleRateHz(),
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
				pcmFormat.sampleRateHz(),
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

		if (!pcmFormat.matches(mAudioRecorder.getSampleRate(), mAudioRecorder.getChannelCount())) {
			Log.e(TAG, "AudioRecord format mismatch: rate=" + mAudioRecorder.getSampleRate()
					+ " channels=" + mAudioRecorder.getChannelCount());
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
		mPcmSession.stopAsync();
		applyPendingPreRollEnabled();
	}
}
