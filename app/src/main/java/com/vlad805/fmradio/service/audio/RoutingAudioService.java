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
import android.os.Environment;
import android.os.Build;
import android.util.Log;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.fm.RecordError;
import com.vlad805.fmradio.service.recording.IAudioRecordable;
import com.vlad805.fmradio.service.recording.IFMRecorder;
import com.vlad805.fmradio.service.recording.RecordService;
import java.util.Timer;
import java.util.TimerTask;

import static com.vlad805.fmradio.helper.Audio.isForceSpeakerNow;

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

	private static final int DEVICE_OUT_FM = resolveDeviceOutFm();

	private final Context mContext;
	private boolean mIsActive = false;
	private boolean mVolumeListenerRegistered = false;
	private AudioRecord mRecordingAudioRecord;
	private Thread mRecordingThread;
	private IFMRecorder mPcmRecorder;
	private long mRecordingStartedMs;
	private Timer mRecordingTimer;

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

			final int device = getCurrentOutputDevice();
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
			applyRoute(true, getCurrentOutputDevice(), true);
			return;
		}

		Log.d(TAG, "startAudio: enabling FM route");
		requestForFocus(true);
		registerVolumeListener();
		mIsActive = true;
		applyRoute(true, getCurrentOutputDevice(), true);
	}

	@Override
	public void stopAudio() {
		if (!mIsActive) {
			Log.d(TAG, "stopAudio: already inactive");
			return;
		}

		stopRecord();
		Log.d(TAG, "stopAudio: disabling FM route");
		applyRoute(false, getCurrentOutputDevice(), false);
		mIsActive = false;
		unregisterVolumeListener();
		requestForFocus(false);
	}

	@Override
	public void setSpeakerEnabled(final boolean enabled) {
		Log.d(TAG, "setSpeakerEnabled: enabled=" + enabled + " active=" + mIsActive);
		if (mIsActive) {
			applyRoute(true, enabled ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_WIRED_HEADPHONES, false);
		}
	}

	private int getCurrentOutputDevice() {
		final boolean speaker = isForceSpeakerNow();
		final int device = speaker ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_WIRED_HEADPHONES;
		Log.d(TAG, "getCurrentOutputDevice: speaker=" + speaker + " device=" + device);
		return device;
	}

	private void applyRoute(final boolean enable, final int device, final boolean updateVolume) {
		if (mAudioManager == null) {
			Log.e(TAG, "applyRoute: AudioManager is null");
			return;
		}

		final int route = enable ? (device | DEVICE_OUT_FM) : device;
		Log.d(TAG, "applyRoute: enable=" + enable + " device=" + device + " deviceOutFm=" + DEVICE_OUT_FM + " route=" + route);

		if (enable) {
			final String fmStatus = mAudioManager.getParameters("fm_status");
			Log.d(TAG, "applyRoute: fm_status=" + fmStatus);
			if (fmStatus != null && fmStatus.contains("1")) {
				final int resetRoute = AudioDeviceInfo.TYPE_WIRED_HEADPHONES | DEVICE_OUT_FM;
				Log.d(TAG, "applyRoute: FM hardwareLoopback already active, resetting via fm_routing=" + resetRoute);
				sendAudioParameter("fm_routing", resetRoute);
			}
			if (updateVolume) {
				applyVolume(device);
			}
			sendAudioParameter("fm_mute", 0);
			sendAudioParameter("handle_fm", route);
			return;
		}

		sendAudioParameter("handle_fm", route);
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
		mRecordingTimer = new Timer("Fm2Record", true);
		mRecordingTimer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				sendRecordingUpdate(C.Event.RECORD_TIME_UPDATE);
			}
		}, RECORDING_UPDATE_MS, RECORDING_UPDATE_MS);
	}

	private void stopRecordingTimer() {
		if (mRecordingTimer != null) {
			mRecordingTimer.cancel();
			mRecordingTimer = null;
		}
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
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return;
		}

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
	private static int resolveDeviceOutFm() {
		try {
			final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
			return audioSystemClass.getField("DEVICE_OUT_FM").getInt(null);
		} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
			Log.e(TAG, "resolveDeviceOutFm failed, fallback to 0", e);
			return 0;
		}
	}
}
