package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.PcmFormat;
import com.vlad805.fmradio.service.recording.PcmRecorderSession;
import com.vlad805.fmradio.service.recording.RdsSnapshot;
import static com.vlad805.fmradio.Utils.sleep;

/**
 * Audio service records sound from
 * a specific source (unavailable for playback through speakers) and outputs it
 * to a regular audio output (headphones or speaker).
 * vlad805 (c) 2019
 */
public abstract class AudioService implements IAudioRecordable {
	protected final AudioManager mAudioManager;

	protected int mSampleRate = 44100; // Default = 8000 (Max with AMR)
	protected int mBufferSize = 16384;
	protected int mAudioSource = 1998;

	/** Session retaining captured PCM and forwarding it to the active recorder. */
	protected volatile PcmRecorderSession mPcmSession;
	protected RdsSnapshot mRdsSnapshot = RdsSnapshot.EMPTY;

	/** Whether recent PCM history should remain active during playback. */
	protected boolean mPreRollEnabled;

	/** Preference value deferred until the active recording is stopped. */
	private Boolean mPendingPreRollEnabled;

	public AudioService(final Context context) {
		this(
				(AudioManager) context.getSystemService(Context.AUDIO_SERVICE),
				Storage.get(context).isRecordingPreRollEnabled()
		);
	}

	public AudioService(final AudioManager audioManager, final boolean preRollEnabled) {
		mAudioManager = audioManager;
		mPreRollEnabled = preRollEnabled;
		mPcmSession = createPcmSession(mPreRollEnabled);
	}

	/**
	 * Start recording audio from a specific source and write to normal audio output
	 */
	public abstract void startAudio();

	/**
	 * Stop redirecting audio
	 */
	public abstract void stopAudio();

	/**
	 * @return PCM format produced by this audio service's capture source.
	 */
	public abstract PcmFormat getPcmFormat();

	protected final PcmRecorderSession createPcmSession(final boolean enabled) {
		final PcmFormat pcmFormat = getPcmFormat();
		return new PcmRecorderSession(
				pcmFormat.sampleRateHz(),
				pcmFormat.channelCount(),
				enabled ? C.Config.RECORDING_PRE_ROLL_SECONDS : 0
		);
	}

	@Override
	public synchronized void updateRds(final RdsSnapshot snapshot) {
		mRdsSnapshot = snapshot;
		mPcmSession.updateRds(snapshot);
	}

	/**
	 * Applies the recording pre-roll preference or defers it during active recording.
	 *
	 * @param enabled Whether recent PCM history should be retained
	 */
	public synchronized void setPreRollEnabled(final boolean enabled) {
		if (mPcmSession.isRecording()) {
			mPendingPreRollEnabled = enabled;
			return;
		}
		applyPreRollEnabled(enabled);
	}

	/** Applies any pre-roll preference change that was deferred while recording. */
	protected synchronized void applyPendingPreRollEnabled() {
		if (mPendingPreRollEnabled != null) {
			applyPreRollEnabled(mPendingPreRollEnabled);
			mPendingPreRollEnabled = null;
		}
	}

	/** Applies the preference by replacing the inactive PCM history buffer. */
	protected void applyPreRollEnabled(final boolean enabled) {
		mPreRollEnabled = enabled;
		mPcmSession = createPcmSession(enabled);
		mPcmSession.updateRds(mRdsSnapshot);
	}

	/**
	 * @return Current FM audio output route.
	 */
	public AudioOutput getOutput() {
		return AudioOutput.HEADPHONES;
	}

	/**
	 * Update FM output route when user switches output device.
	 */
	public void setOutput(final AudioOutput output) {
		// Default implementation does nothing.
	}

	/**
	 * AudioSource:
	 * DEFAULT              work, HQ, loud
	 * MIC                  work, HW
	 * VOICE_UPLINK         not work
	 * VOICE_DOWNLINK       not work
	 * VOICE_CALL           not work
	 * CAMCORDER            work, LQ, very sensitive
	 * VOICE_RECOGNITION    work, LQ, only left channel
	 * VOICE_COMMUNICATION  work, LQ, very sensitive
	 * REMOTE_SUBMIX        not work
	 * 1998 = FM            work (Mi A1) / not work (Xperia L)
	 */
	protected AudioRecord getAudioRecorder() {
		// AUDIO_CHANNEL_IN_FRONT_BACK?
		try {
			final AudioRecord recorder = new AudioRecord(
					mAudioSource,
					mSampleRate,
					AudioFormat.CHANNEL_IN_STEREO,
					AudioFormat.ENCODING_PCM_16BIT,
					mBufferSize
			);

			sleep(200);

			if (recorder.getState() == AudioRecord.STATE_INITIALIZED) { // If works, then done
				return recorder;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	protected void requestForFocus(boolean needFocus) {
		if (needFocus) { // If focus desired...
			mAudioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
		} else { // If focus return...
			mAudioManager.abandonAudioFocus(null);
		}
	}
}
