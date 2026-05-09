package com.vlad805.fmradio.service.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.util.Log;

import com.vlad805.fmradio.enums.AudioOutputRoute;
import static com.vlad805.fmradio.Utils.sleep;

/**
 * Audio service records sound from
 * a specific source (unavailable for playback through speakers) and outputs it
 * to a regular audio output (headphones or speaker).
 * vlad805 (c) 2019
 */
public abstract class AudioService {
	private static final String TAG = "AudioService";
	protected final AudioManager mAudioManager;

	protected int mSampleRate = 44100; // Default = 8000 (Max with AMR)
	protected int mBufferSize = 16384;
	protected int mAudioSource = 1998;

	public AudioService(Context context) {
		mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
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
	 * Update FM output route when user toggles speaker mode.
	 */
	public AudioOutputRoute setOutputRoute(final AudioOutputRoute route) {
		return route == null ? AudioOutputRoute.WIRED : route;
	}

	public AudioOutputRoute getOutputRoute() {
		return AudioOutputRoute.WIRED;
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

	protected interface PcmDataConsumer {
		void onPcmData(short[] buffer, int length);
	}

	protected static final class PcmBridge {
		private final String mThreadName;
		private volatile boolean mActive;
		private AudioRecord mInput;
		private AudioTrack mOutput;
		private Thread mThread;

		PcmBridge(final String threadName) {
			mThreadName = threadName;
		}

		boolean isActive() {
			return mActive;
		}

		boolean start(final AudioRecord input, final AudioTrack output, final int bufferShorts, final PcmDataConsumer consumer) {
			if (mActive) {
				return true;
			}

			if (input == null || output == null || bufferShorts <= 0) {
				return false;
			}

			try {
				input.startRecording();
				output.play();
			} catch (Throwable t) {
				Log.e(TAG, "PcmBridge.start failed", t);
				safeRelease(input, output);
				return false;
			}

			mInput = input;
			mOutput = output;
			mActive = true;
			mThread = new Thread(() -> {
				final short[] buffer = new short[bufferShorts];
				while (mActive && mInput == input && !Thread.currentThread().isInterrupted()) {
					final int read = input.read(buffer, 0, buffer.length);
					if (read <= 0) {
						continue;
					}

					output.write(buffer, 0, read);
					if (consumer != null) {
						consumer.onPcmData(buffer, read);
					}
				}
			}, mThreadName);
			mThread.start();
			return true;
		}

		void stop() {
			mActive = false;
			if (mThread != null) {
				mThread.interrupt();
				mThread = null;
			}

			final AudioRecord input = mInput;
			final AudioTrack output = mOutput;
			mInput = null;
			mOutput = null;
			safeRelease(input, output);
		}

		private static void safeRelease(final AudioRecord input, final AudioTrack output) {
			if (input != null) {
				try {
					input.stop();
				} catch (Throwable ignored) {
				}
				try {
					input.release();
				} catch (Throwable ignored) {
				}
			}

			if (output != null) {
				try {
					output.pause();
					output.flush();
					output.release();
				} catch (Throwable ignored) {
				}
			}
		}
	}
}
