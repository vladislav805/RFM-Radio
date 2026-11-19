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

	private final Context mContext;
	private final RoutedAudioPolicy mPolicy;
	private boolean mIsActive = false;
	private boolean mVolumeListenerRegistered = false;
	private AudioOutput mOutput = AudioOutput.HEADPHONES;

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

		final AudioRecord audioRecord;
		try {
			audioRecord = new AudioRecord.Builder()
					.setAudioSource(MediaRecorder.AudioSource.DEFAULT)
					.setAudioFormat(format)
					.setBufferSizeInBytes(bufferSize * 2)
					.build();
		} catch (Throwable t) {
			Log.e(TAG, "startAudioRecordRecorder: AudioRecord build failed", t);
			throw new RecordError("Cannot create AudioRecord");
		}

		if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
			audioRecord.release();
			throw new RecordError("AudioRecord init failed");
		}

		if (!pcmFormat.matches(audioRecord.getSampleRate(), audioRecord.getChannelCount())) {
			final int actualRate = audioRecord.getSampleRate();
			final int actualChannels = audioRecord.getChannelCount();
			audioRecord.release();
			throw new RecordError("AudioRecord format mismatch: " + actualRate + " Hz, " + actualChannels + " ch");
		}

		if (!audioRecord.setPreferredDevice(fmTuner)) {
			Log.w(TAG, "startAudioRecordRecorder: setPreferredDevice(FM Tuner) returned false");
		}

		try {
			audioRecord.startRecording();
		} catch (Throwable t) {
			audioRecord.release();
			throw new RecordError("AudioRecord.startRecording failed");
		}

		mRecordingAudioRecord = audioRecord;

		mRecordingThread = new Thread(() -> {
			final short[] buffer = new short[bufferSize / 2];
			try {
				while (mRecordingAudioRecord == audioRecord && !Thread.currentThread().isInterrupted()) {
					final int read = audioRecord.read(buffer, 0, buffer.length);
					if (read > 0) {
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
