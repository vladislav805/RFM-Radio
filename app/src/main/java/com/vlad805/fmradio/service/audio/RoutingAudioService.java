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
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.IFMRecorder;
import com.vlad805.fmradio.service.recording.RecordService;

/**
 * FM2/Helium devices route FM audio directly inside audio HAL.
 * No AudioRecord bridge is needed here.
 */
public class RoutingAudioService extends AudioService implements IAudioRecordable {
	private static final String TAG = "QFm2Audio";

	// Values from @hide Android API
	private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
	private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
	private static final int RECORDING_UPDATE_MS = 1000;

	private static final int DEVICE_OUT_FM = resolveAudioSystemDevice("DEVICE_OUT_FM", 0);
	private static final int DEVICE_OUT_SPEAKER = resolveAudioSystemDevice("DEVICE_OUT_SPEAKER", 0x2);
	private static final int DEVICE_OUT_WIRED_HEADSET = resolveAudioSystemDevice("DEVICE_OUT_WIRED_HEADSET", 0x4);

    private final Context mContext;
	private boolean mIsActive = false;
	private boolean mVolumeListenerRegistered = false;
	private boolean mSpeakerEnabled = false;
	private AudioRecord mRecordingAudioRecord;
	private Thread mRecordingThread;
	private IFMRecorder mPcmRecorder;
	private long mRecordingStartedMs;
	private final Handler mRecordingHandler = new Handler(Looper.getMainLooper());
	private final Runnable mRecordingUpdateRunnable = new Runnable() {
		@Override
		public void run() {
			if (mRecordingAudioRecord == null) {
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

				final int device = getCurrentVolumeDeviceType();
				Log.d(TAG, "onVolumeChanged: refreshing fm_volume for device=" + device);
				applyVolume(device);
			}
	};

	public RoutingAudioService(final Context context) {
		super(context);
		mContext = context.getApplicationContext();
	}

	@Override
	public void startAudio() {
		if (mIsActive) {
			Log.d(TAG, "startAudio: already active, refreshing route");
			applyRoute(true, mSpeakerEnabled, true);
			return;
		}

		Log.d(TAG, "startAudio: enabling FM route");
		requestForFocus(true);
		registerVolumeListener();
		mIsActive = true;
		applyRoute(true, mSpeakerEnabled, true);
	}

	@Override
	public void stopAudio() {
		if (!mIsActive) {
			Log.d(TAG, "stopAudio: already inactive");
			return;
		}

		stopRecord();
		Log.d(TAG, "stopAudio: disabling FM route");
		applyRoute(false, mSpeakerEnabled, false);
		mIsActive = false;
		unregisterVolumeListener();
		requestForFocus(false);
	}

	@Override
	public void setSpeakerEnabled(final boolean enabled) {
		Log.d(TAG, "setSpeakerEnabled: enabled=" + enabled + " active=" + mIsActive);
		mSpeakerEnabled = enabled;
		if (mIsActive) {
			applyRoute(true, enabled, false);
		}
	}

	private int getCurrentVolumeDeviceType() {
		return mSpeakerEnabled ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
	}

	private void applyRoute(final boolean enable, final boolean speakerEnabled, final boolean updateVolume) {
		if (mAudioManager == null) {
			Log.e(TAG, "applyRoute: AudioManager is null");
			return;
		}

		final int routeDevice = speakerEnabled ? DEVICE_OUT_SPEAKER : DEVICE_OUT_WIRED_HEADSET;
		final int volumeDeviceType = speakerEnabled ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
		final int route = enable ? (routeDevice | DEVICE_OUT_FM) : routeDevice;
		Log.d(TAG, "applyRoute: enable=" + enable + " speaker=" + speakerEnabled + " routeDevice=" + routeDevice + " deviceOutFm=" + DEVICE_OUT_FM + " route=" + route);
		applyFrameworkRoute(speakerEnabled);

		if (enable) {
			final String fmStatus = mAudioManager.getParameters("fm_status");
			Log.d(TAG, "applyRoute: fm_status=" + fmStatus);
			if (fmStatus != null && fmStatus.contains("1")) {
				// AudioManager routing parameters use AudioSystem.DEVICE_OUT_* masks, not AudioDeviceInfo.TYPE_* constants.
				final int resetRoute = DEVICE_OUT_WIRED_HEADSET | DEVICE_OUT_FM;
				Log.d(TAG, "applyRoute: FM hardwareLoopback already active, resetting via fm_routing=" + resetRoute);
				sendAudioParameter("fm_routing", resetRoute);
			}
			if (updateVolume) {
				applyVolume(volumeDeviceType);
			}
			sendAudioParameter("fm_mute", 0);
			sendAudioParameter("fm_routing", route);
			sendAudioParameter("handle_fm", route);
			return;
		}

		sendAudioParameter("fm_routing", route);
		sendAudioParameter("handle_fm", route);
	}

	private void applyFrameworkRoute(final boolean speakerEnabled) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				if (speakerEnabled) {
					for (final AudioDeviceInfo device : mAudioManager.getAvailableCommunicationDevices()) {
						if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
							mAudioManager.setCommunicationDevice(device);
							return;
						}
					}
				} else {
					mAudioManager.clearCommunicationDevice();
				}
			}
			mAudioManager.setSpeakerphoneOn(speakerEnabled);
		} catch (Throwable t) {
			Log.w(TAG, "applyFrameworkRoute failed for speaker=" + speakerEnabled, t);
		}
	}

	private void applyVolume(final int device) {
		try {
			final int volumeIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			final float volume = resolveMusicStreamGain(volumeIndex, device);
			Log.d(TAG, "applyVolume: index=" + volumeIndex + " volume=" + volume);
			sendAudioParameter("fm_volume", String.valueOf(volume));
		} catch (Throwable t) {
			Log.e(TAG, "applyVolume failed", t);
		}
	}

	private float resolveMusicStreamGain(final int volumeIndex, final int device) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			final float decibels = mAudioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, volumeIndex, device);
			final float volume = (float) Math.exp(decibels * 0.115129f);
			Log.d(TAG, "resolveMusicStreamGain: dB=" + decibels);
			return volume;
		}

		final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
		if (maxVolume <= 0) {
			return 0f;
		}

		return Math.max(0f, Math.min(1f, volumeIndex / (float) maxVolume));
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

	private void sendAudioParameter(final String key, final int value) {
		sendAudioParameter(key, String.valueOf(value));
	}

	private void sendAudioParameter(final String key, final String value) {
		final String pair = key + "=" + value;
		try {
			Log.d(TAG, "setParameters: " + pair);
			mAudioManager.setParameters(pair);
		} catch (Throwable t) {
			Log.e(TAG, "setParameters failed: " + pair, t);
		}
	}

	@RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
	public void startRecord(final IFMRecorder recorder) throws RecordError {
		if (mRecordingAudioRecord != null) {
			Log.d(TAG, "startRecord: already recording");
			return;
		}

		if (
				Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
				&&
				!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
		) {
			throw new RecordError("External storage is not mounted");
		}

		ensureRecordingPermissions();

		startAudioRecordRecorder(recorder);
	}

	@RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void startAudioRecordRecorder(final IFMRecorder recorder) throws RecordError {
		final AudioDeviceInfo fmTuner = findFmTunerInputDevice();
		if (fmTuner == null) {
			throw new RecordError("FM Tuner input device not found");
		}

		final int bufferSize = AudioRecord.getMinBufferSize(
				48000,
				AudioFormat.CHANNEL_IN_STEREO,
				AudioFormat.ENCODING_PCM_16BIT
		);
		if (bufferSize <= 0) {
			recorder.stopRecord();
			throw new RecordError("AudioRecord buffer init failed");
		}

		final AudioFormat format = new AudioFormat.Builder()
				.setSampleRate(48000)
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

		if (!audioRecord.setPreferredDevice(fmTuner)) {
			Log.w(TAG, "startAudioRecordRecorder: setPreferredDevice(FM Tuner) returned false");
		}

		try {
			audioRecord.startRecording();
		} catch (Throwable t) {
			audioRecord.release();
			throw new RecordError("AudioRecord.startRecording failed");
		}

		try {
			recorder.startRecord();
		} catch (RecordError e) {
			try {
				audioRecord.stop();
			} catch (Throwable ignored) {
			}
			audioRecord.release();
			throw e;
		}

		mRecordingAudioRecord = audioRecord;
		mPcmRecorder = recorder;
		mRecordingStartedMs = System.currentTimeMillis();
		startRecordingTimer();

		mRecordingThread = new Thread(() -> {
			final short[] buffer = new short[bufferSize / 2];
			while (mRecordingAudioRecord == audioRecord && !Thread.currentThread().isInterrupted()) {
				final int read = audioRecord.read(buffer, 0, buffer.length);
				if (read > 0 && mPcmRecorder != null) {
					mPcmRecorder.record(buffer, read);
				}
			}
		}, "Fm2RecordPcm");
		mRecordingThread.start();
	}

	@Override
	public void stopRecord() {
		if (mRecordingAudioRecord == null) {
			return;
		}

		Log.d(TAG, "stopRecord");
		stopRecordingTimer();

		if (mRecordingThread != null) {
			mRecordingThread.interrupt();
			mRecordingThread = null;
		}
		if (mRecordingAudioRecord != null) {
			try {
				mRecordingAudioRecord.stop();
			} catch (Throwable t) {
				Log.w(TAG, "AudioRecord.stop failed", t);
			}
			try {
				mRecordingAudioRecord.release();
			} catch (Throwable ignored) {
			}
			mRecordingAudioRecord = null;
		}
		if (mPcmRecorder != null) {
			mPcmRecorder.stopRecord();
			mPcmRecorder = null;
		}
		sendRecordingUpdate(C.Event.RECORD_ENDED);
		mRecordingStartedMs = 0L;
	}

	private void startRecordingTimer() {
		stopRecordingTimer();
		mRecordingHandler.postDelayed(mRecordingUpdateRunnable, RECORDING_UPDATE_MS);
	}

	private void stopRecordingTimer() {
		mRecordingHandler.removeCallbacks(mRecordingUpdateRunnable);
	}

	private void sendRecordingUpdate(final String event) {
		final String displayPath = getRecordingDisplayPath();
		if (displayPath == null) {
			return;
		}

		final long durationSec = mRecordingStartedMs > 0L
				? Math.max(0L, (System.currentTimeMillis() - mRecordingStartedMs) / 1000L)
				: 0L;
		final long fileSize = getRecordingSizeBytes();

		mContext.sendBroadcast(new Intent(event)
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

	private void ensureRecordingPermissions() throws RecordError {
        if (mContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			throw new RecordError("Please allow microphone access before recording");
		}

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
				&& mContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			throw new RecordError("Please allow storage access before recording");
		}
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

	@SuppressLint("PrivateApi")
	private static int resolveAudioSystemDevice(final String fieldName, final int fallbackValue) {
		try {
			final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
			return audioSystemClass.getField(fieldName).getInt(null);
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
			Log.e(TAG, "resolveAudioSystemDevice failed for " + fieldName + ", fallback=" + fallbackValue, e);
			return fallbackValue;
		}
	}
}
