package com.vlad805.fmradio.service.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.vlad805.fmradio.C;
import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IFMRecorder;
import com.vlad805.fmradio.service.recording.PcmFormat;
import com.vlad805.fmradio.service.recording.RecordService;
import com.vlad805.fmradio.service.recording.RecordingStorage;

/**
 * FM2/Helium devices route FM audio directly inside audio HAL.
 * No AudioRecord bridge is needed here.
 */
public class RoutingAudioService extends AudioService {
	private static final String TAG = "QFm2Audio";

	// Values from @hide Android API
	private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
	private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
	private static final int RECORDING_UPDATE_MS = 1000;
	private static final int SAMSUNG_FM_AUDIO_SOURCE = 11;
	private static final int[] S610_VOLUME_GAINS = {
			0, 16, 23, 32, 45, 64, 90, 128, 181, 256, 362, 512, 724, 1024, 1447, 2047
	};
	private static final float S610_MAX_COMPENSATION_GAIN = 64.0f;

	private final Context mContext;
	private final RoutedAudioPolicy mPolicy;
	private boolean mIsActive = false;
	private boolean mVolumeListenerRegistered = false;
	private AudioOutput mOutput = AudioOutput.HEADPHONES;
	private MediaSession mExynosRecordingVolumeSession;
	private volatile VolumeProvider mExynosRecordingVolumeProvider;

	/** FM input capture kept alive while pre-roll or on-demand recording is active. */
	private AudioRecord mRecordingAudioRecord;

	/** Worker continuously reading PCM from {@link #mRecordingAudioRecord}. */
	private Thread mRecordingThread;

	/** Destination recorder used by the current recording request. */
	private IFMRecorder mPcmRecorder;

	/** Whether the current record action had to start an on-demand FM capture. */
	private boolean mCaptureStartedForRecording;

	/** Main-thread handler publishing recording progress. */
	private final Handler mRecordingHandler = new Handler(Looper.getMainLooper());

	/** Periodically refreshes duration and file size while recording. */
	private final Runnable mRecordingUpdateRunnable = new Runnable() {
		@Override
		public void run() {
			if (mPcmRecorder == null) {
				return;
			}

			sendRecordingUpdate(C.Event.RECORD_TIME_UPDATE);
			mRecordingHandler.postDelayed(this, RECORDING_UPDATE_MS);
		}
	};

	private final BroadcastReceiver mVolumeReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			if (intent == null || !ACTION_VOLUME_CHANGED.equals(intent.getAction()) || !mIsActive) {
				return;
			}

			final int streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
			if (streamType != AudioManager.STREAM_MUSIC) {
				return;
			}

			mPolicy.onMusicVolumeChanged(mOutput);
		}
	};

	public RoutingAudioService(final Context context, final RoutedAudioPolicy policy) {
		super(context);
		mContext = context.getApplicationContext();
		mPolicy = policy;
	}

	@Override
	public void startAudio() {
		if (mIsActive) {
			Log.d(TAG, "startAudio: already active, refreshing route");
			mPolicy.applyRoute(true, mOutput, true);
			return;
		}

		Log.d(TAG, "startAudio: enabling FM route");
		requestForFocus(true);
		registerVolumeListener();
		mIsActive = true;
		mPolicy.applyRoute(true, mOutput, true);
		if (mPreRollEnabled && hasRecordAudioPermission()) {
			try {
				startPcmCapture();
			} catch (RecordError error) {
				Log.w(TAG, "Cannot start pre-roll capture", error);
			}
		}
	}

	@Override
	public void stopAudio() {
		if (!mIsActive) {
			Log.d(TAG, "stopAudio: already inactive");
			return;
		}

		stopRecord();
		stopPcmCapture();
		mPcmSession.clearHistory();
		Log.d(TAG, "stopAudio: disabling FM route");
		mPolicy.applyRoute(false, mOutput, false);
		mIsActive = false;
		unregisterVolumeListener();
		requestForFocus(false);
	}

	@Override
	public PcmFormat getPcmFormat() {
		return PcmFormat.PCM16_STEREO_48000;
	}

	@Override
	public AudioOutput getOutput() {
		return mPolicy.getOutput(mOutput);
	}

	@Override
	public void setOutput(final AudioOutput output) {
		Log.d(TAG, "setOutput: output=" + output + " active=" + mIsActive);
		mOutput = output;
		if (mIsActive) {
			mPolicy.applyRoute(true, output, false);
		}
		mPolicy.afterOutputChanged(output);
	}

	private void registerVolumeListener() {
		if (mVolumeListenerRegistered) {
			return;
		}
		mContext.registerReceiver(mVolumeReceiver, new IntentFilter(ACTION_VOLUME_CHANGED));
		mVolumeListenerRegistered = true;
	}

	private void unregisterVolumeListener() {
		if (!mVolumeListenerRegistered) {
			return;
		}
		try {
			mContext.unregisterReceiver(mVolumeReceiver);
		} catch (Throwable t) {
			Log.w(TAG, "unregisterVolumeListener failed", t);
		}
		mVolumeListenerRegistered = false;
	}

	@RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
	public void startRecord(final IFMRecorder recorder) throws RecordError {
		if (mPcmRecorder != null) {
			Log.d(TAG, "startRecord: already recording");
			return;
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
				&& !Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
		) {
			throw new RecordError("External storage is not mounted");
		}

		ensureRecordingPermissions();

		mCaptureStartedForRecording = mRecordingAudioRecord == null;
		if (mCaptureStartedForRecording) {
			startPcmCapture();
		}

		try {
			mPcmSession.start(recorder);
		} catch (RecordError error) {
			if (mCaptureStartedForRecording) {
				stopPcmCapture();
			}
			throw error;
		}

		mPcmRecorder = recorder;
		startRecordingTimer();
	}

	/**
	 * Starts continuous PCM capture from Android's FM tuner input device.
	 *
	 * @throws RecordError If the FM input cannot be opened
	 */
	@SuppressLint("MissingPermission")
	private void startPcmCapture() throws RecordError {
		if (mRecordingAudioRecord != null) {
			return;
		}
		if (!hasRecordAudioPermission()) {
			throw new RecordError("Please allow microphone access before recording");
		}

		final AudioDeviceInfo fmTuner = findFmTunerInputDevice();
		if (fmTuner == null) {
			throw new RecordError("FM Tuner input device not found");
		}

		final PcmFormat pcmFormat = getPcmFormat();
		final int bufferSize = AudioRecord.getMinBufferSize(
				pcmFormat.sampleRateHz(),
				AudioFormat.CHANNEL_IN_STEREO,
				AudioFormat.ENCODING_PCM_16BIT
		);
		if (bufferSize <= 0) {
			throw new RecordError("AudioRecord buffer init failed");
		}

		final AudioFormat format = new AudioFormat.Builder()
				.setSampleRate(pcmFormat.sampleRateHz())
				.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
				.setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
				.build();

		final SamsungS610Audio exynosPolicy = mPolicy instanceof SamsungS610Audio
				? (SamsungS610Audio) mPolicy
				: null;
		final boolean isExynos = exynosPolicy != null;
		if (isExynos && mAudioManager != null) {
			// Intentionally spelled "fmradio_recoding" (without 'r') to match Samsung's audio HAL key.
			mAudioManager.setParameters("fmradio_recoding=on");
		}

		final AudioRecord audioRecord = createFmAudioRecord(format, bufferSize * 2, fmTuner, pcmFormat, isExynos);
		if (audioRecord == null) {
			if (isExynos && mAudioManager != null) {
				mAudioManager.setParameters("fmradio_recoding=off");
			}
			throw new RecordError("AudioRecord init failed");
		}

		mRecordingAudioRecord = audioRecord;
		if (isExynos) {
			startExynosRecordingVolumeSession(exynosPolicy);
		}

		mRecordingThread = new Thread(() -> {
			final short[] buffer = new short[bufferSize / 2];
			try {
				while (mRecordingAudioRecord == audioRecord && !Thread.currentThread().isInterrupted()) {
					final int read = audioRecord.read(buffer, 0, buffer.length);
					if (read > 0) {
						if (exynosPolicy != null) {
							compensateExynosVolume(buffer, read, exynosPolicy.getLastVolume());
						}
						mPcmSession.append(buffer, read);
					} else if (read == AudioRecord.ERROR_DEAD_OBJECT) {
						Log.e(TAG, "FM capture audio server died");
						break;
					} else {
						Log.w(TAG, "FM capture read returned " + read);
						SystemClock.sleep(read < 0 ? 100 : 10);
					}
				}
			} finally {
				boolean ownsAudioRecord = false;
				synchronized (RoutingAudioService.this) {
					if (mRecordingAudioRecord == audioRecord) {
						mRecordingAudioRecord = null;
						mRecordingThread = null;
						ownsAudioRecord = true;
					}
				}
				if (ownsAudioRecord) {
					if (exynosPolicy != null) {
						releaseExynosRecordingVolumeSession(exynosPolicy);
						if (mAudioManager != null) {
							mAudioManager.setParameters("fmradio_recoding=off");
						}
					}
					audioRecord.release();
				}
			}
		}, "Fm2RecordPcm");
		mRecordingThread.start();
	}

	@Override
	public void stopRecord() {
		if (mPcmRecorder == null) {
			return;
		}

		Log.d(TAG, "stopRecord");
		stopRecordingTimer();
		mPcmSession.stop();
		mPcmRecorder = null;

		if (mCaptureStartedForRecording && !mPreRollEnabled) {
			stopPcmCapture();
		}
		mCaptureStartedForRecording = false;
		applyPendingPreRollEnabled();
	}

	/** Reconfigures PCM history and starts or stops persistent HAL capture. */
	@Override
	protected void applyPreRollEnabled(final boolean enabled) {
		if (!enabled) {
			stopPcmCapture();
		}

		super.applyPreRollEnabled(enabled);
		if (enabled && mIsActive && hasRecordAudioPermission()) {
			try {
				startPcmCapture();
			} catch (RecordError error) {
				Log.w(TAG, "Cannot start pre-roll capture", error);
			}
		}
	}

	/** Stops the HAL capture worker and releases its {@link AudioRecord}. */
	private void stopPcmCapture() {
		final AudioRecord audioRecord;
		final Thread recordingThread;

		// Detach the capture first so the read loop cannot publish more PCM.
		synchronized (this) {
			audioRecord = mRecordingAudioRecord;
			recordingThread = mRecordingThread;
			mRecordingAudioRecord = null;
			mRecordingThread = null;
		}

		if (audioRecord == null) {
			return;
		}

		if (recordingThread != null) {
			recordingThread.interrupt();
		}
		// AudioRecord.stop() unblocks a pending read on supported HALs.
		try {
			audioRecord.stop();
		} catch (Throwable error) {
			Log.w(TAG, "AudioRecord.stop failed", error);
		}

		// The AudioRecord must not be released while its worker can still use it.
		if (recordingThread != null && recordingThread != Thread.currentThread()) {
			boolean interrupted = false;
			while (recordingThread.isAlive()) {
				try {
					recordingThread.join();
				} catch (InterruptedException error) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		audioRecord.release();
		if (mPolicy instanceof SamsungS610Audio) {
			releaseExynosRecordingVolumeSession((SamsungS610Audio) mPolicy);
			if (mAudioManager != null) {
				mAudioManager.setParameters("fmradio_recoding=off");
			}
		}
	}

	private synchronized void startExynosRecordingVolumeSession(final SamsungS610Audio exynosPolicy) {
		if (mAudioManager == null || mExynosRecordingVolumeSession != null) {
			return;
		}
		final int maxVolume = Math.max(1, mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
		final int currentVolume = Math.max(
				0,
				Math.min(maxVolume, mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
		);
		final VolumeProvider provider = new VolumeProvider(
				VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
				maxVolume,
				currentVolume
		) {
			@Override
			public void onAdjustVolume(final int direction) {
				if (direction != 0) {
					adjustExynosStreamVolume(direction);
				}
			}

			@Override
			public void onSetVolumeTo(final int volume) {
				setExynosStreamVolume(volume);
			}
		};

		final MediaSession session = new MediaSession(mContext, TAG + "-RecVol");
		session.setCallback(new MediaSession.Callback() {
		}, mRecordingHandler);
		session.setFlags(
				MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
						| MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
		);
		session.setPlaybackState(new PlaybackState.Builder()
				.setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
				.build());
		session.setPlaybackToRemote(provider);
		session.setActive(true);

		mExynosRecordingVolumeProvider = provider;
		mExynosRecordingVolumeSession = session;
		exynosPolicy.setRecordingVolumeProvider(provider);
	}

	private synchronized void releaseExynosRecordingVolumeSession(final SamsungS610Audio exynosPolicy) {
		final MediaSession session = mExynosRecordingVolumeSession;
		mExynosRecordingVolumeSession = null;
		mExynosRecordingVolumeProvider = null;
		exynosPolicy.setRecordingVolumeProvider(null);
		if (session != null) {
			try {
				session.setActive(false);
				session.release();
			} catch (Throwable t) {
				Log.w(TAG, "Failed to release Exynos recording MediaSession", t);
			}
		}
	}

	static int computeAdjustedStreamVolume(
			final int currentVolume,
			final int maxVolume,
			final int direction
	) {
		final int step;
		if (direction == AudioManager.ADJUST_RAISE) {
			step = 1;
		} else if (direction == AudioManager.ADJUST_LOWER) {
			step = -1;
		} else if (direction == AudioManager.ADJUST_MUTE) {
			return 0;
		} else {
			return Math.max(0, Math.min(maxVolume, currentVolume));
		}
		return Math.max(0, Math.min(maxVolume, currentVolume + step));
	}

	private void adjustExynosStreamVolume(final int direction) {
		if (mAudioManager == null) {
			return;
		}
		final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		final int currentVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		final int target = computeAdjustedStreamVolume(currentVolume, maxVolume, direction);
		if (target != currentVolume) {
			setExynosStreamVolume(target);
		}
	}

	private void setExynosStreamVolume(final int volume) {
		if (mAudioManager == null) {
			return;
		}
		final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		final int target = Math.max(0, Math.min(maxVolume, volume));
		Log.d(TAG, "setExynosStreamVolume: setting STREAM_MUSIC=" + target + "/" + maxVolume);
		mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
		final VolumeProvider provider = mExynosRecordingVolumeProvider;
		if (provider != null) {
			provider.setCurrentVolume(target);
		}
	}

	@SuppressLint("MissingPermission")
	private AudioRecord createFmAudioRecord(
			final AudioFormat format,
			final int bufferBytes,
			final AudioDeviceInfo fmTuner,
			final PcmFormat pcmFormat,
			final boolean isExynos
	) {
		final int[] sources = isExynos
				? new int[]{SAMSUNG_FM_AUDIO_SOURCE, MediaRecorder.AudioSource.DEFAULT}
				: new int[]{MediaRecorder.AudioSource.DEFAULT};
		for (final int source : sources) {
			AudioRecord candidate = null;
			try {
				candidate = new AudioRecord.Builder()
						.setAudioSource(source)
						.setAudioFormat(format)
						.setBufferSizeInBytes(bufferBytes)
						.build();
				if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
					candidate.release();
					continue;
				}
				if (!pcmFormat.matches(candidate.getSampleRate(), candidate.getChannelCount())) {
					candidate.release();
					continue;
				}
				if (!candidate.setPreferredDevice(fmTuner)) {
					Log.w(TAG, "createFmAudioRecord: setPreferredDevice(FM Tuner) returned false for source=" + source);
				}
				candidate.startRecording();
				Log.d(TAG, "createFmAudioRecord: started FM capture with source=" + source);
				return candidate;
			} catch (Throwable t) {
				Log.w(TAG, "createFmAudioRecord: source=" + source + " failed", t);
				if (candidate != null) {
					candidate.release();
				}
			}
		}
		return null;
	}

	static void compensateExynosVolume(final short[] buffer, final int length, final int volumeIndex) {
		if (buffer == null || volumeIndex <= 0 || volumeIndex >= S610_VOLUME_GAINS.length - 1) {
			return;
		}
		final int chipGain = S610_VOLUME_GAINS[volumeIndex];
		if (chipGain <= 0) {
			return;
		}
		final int fullScaleGain = S610_VOLUME_GAINS[S610_VOLUME_GAINS.length - 1];
		final int maxScaledNumerator = Math.round(S610_MAX_COMPENSATION_GAIN * chipGain);
		final int numerator = Math.min(fullScaleGain, maxScaledNumerator);
		final int halfDenominator = chipGain / 2;
		final int limit = Math.min(length, buffer.length);
		for (int i = 0; i < limit; ++i) {
			final int sample = buffer[i];
			final int scaled = sample >= 0
					? (sample * numerator + halfDenominator) / chipGain
					: (sample * numerator - halfDenominator) / chipGain;
			if (scaled > Short.MAX_VALUE) {
				buffer[i] = Short.MAX_VALUE;
			} else if (scaled < Short.MIN_VALUE) {
				buffer[i] = Short.MIN_VALUE;
			} else {
				buffer[i] = (short) scaled;
			}
		}
	}

	/** Starts periodic recording progress broadcasts. */
	private void startRecordingTimer() {
		stopRecordingTimer();
		mRecordingHandler.postDelayed(mRecordingUpdateRunnable, RECORDING_UPDATE_MS);
	}

	/** Stops periodic recording progress broadcasts. */
	private void stopRecordingTimer() {
		mRecordingHandler.removeCallbacks(mRecordingUpdateRunnable);
	}

	/** Sends current recording metadata to UI and notification receivers. */
	private void sendRecordingUpdate(final String event) {
		final String displayPath = getRecordingDisplayPath();
		if (displayPath == null) {
			return;
		}

		final long durationSec = getRecordingDurationSeconds();
		final long fileSize = getRecordingSizeBytes();

		Utils.sendAppBroadcast(mContext, new Intent(event)
				.putExtra(C.Key.SIZE, (int) Math.min(Integer.MAX_VALUE, fileSize))
				.putExtra(C.Key.DURATION, (int) Math.min(Integer.MAX_VALUE, durationSec))
				.putExtra(C.Key.PATH, displayPath));
	}

	private String getRecordingDisplayPath() {
		if (mPcmRecorder instanceof RecordService) {
			return ((RecordService) mPcmRecorder).getDisplayPath();
		}
		return null;
	}

	private long getRecordingSizeBytes() {
		if (mPcmRecorder instanceof RecordService) {
			return ((RecordService) mPcmRecorder).getCurrentSizeBytes();
		}
		return 0L;
	}

	/** @return Current duration based on accepted PCM samples. */
	private long getRecordingDurationSeconds() {
		if (mPcmRecorder instanceof RecordService) {
			return ((RecordService) mPcmRecorder).getDurationSeconds();
		}
		return 0L;
	}

	private void ensureRecordingPermissions() throws RecordError {
		if (!hasRecordAudioPermission()) {
			throw new RecordError("Please allow microphone access before recording");
		}

		if (
				RecordingStorage.requiresStoragePermission(mContext)
				&&
				mContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
		) {
			throw new RecordError("Please allow storage access before recording");
		}
	}

	/** @return Whether microphone access needed for FM input capture is granted. */
	private boolean hasRecordAudioPermission() {
		return mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
	}

	private AudioDeviceInfo findFmTunerInputDevice() {
		if (mAudioManager == null) {
			return null;
		}
		for (final AudioDeviceInfo device : mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
			Log.d(TAG, "input device: type=" + device.getType() + " product=" + device.getProductName());
			if (device.getType() == AudioDeviceInfo.TYPE_FM_TUNER) {
				Log.d(TAG, "findFmTunerInputDevice: found " + device.getProductName());
				return device;
			}
		}
		return null;
	}
}
