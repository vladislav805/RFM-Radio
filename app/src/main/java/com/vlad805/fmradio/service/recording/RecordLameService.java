package com.vlad805.fmradio.service.recording;

import android.content.Context;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * vlad805 (c) 2021
 */
public class RecordLameService extends RecordService implements IFMRecorder {
	private static final int CHANNELS = 2;
	private static final int QUALITY = 5;
	// Output buffer for final MP3 frames emitted when LAME flushes its internal delay/padding.
	private static final int MP3_FLUSH_BUFFER_SIZE = 7200;
	/** CBR bitrate selected by the recording format preference. */
	private final int mBitrateKbps;
	private LameMp3Encoder mEncoder;

	/**
	 * @param context Context
	 * @param kHz Current frequency in kHz
	 * @param sampleRate PCM sample rate in Hz
	 * @param bitrateKbps MP3 CBR bitrate in kbps
	 */
	public RecordLameService(
			final Context context,
			final int kHz,
			final int sampleRate,
			final int bitrateKbps,
			@Nullable final RecordingListener listener
	) {
		super(context, kHz, sampleRate, listener);
		mBitrateKbps = bitrateKbps;
	}

	@Override
	protected String getExtension() {
		return "mp3";
	}

	@Override
	protected String getMimeType() {
		return "audio/mpeg";
	}

	/**
	 * When recording is being started, need create instance of encoder lame
	 */
	@Override
	protected void onFileCreated() throws IOException {
		mEncoder = new LameMp3Encoder(getSampleRate(), CHANNELS, mBitrateKbps, QUALITY);
	}

	/**
	 * Encode chunk of data with lame
	 */
	@Override
	protected int writePcm(short[] data, int length) throws IOException {
		byte[] encoded = new byte[7200 + CHANNELS * length];
		int written = mEncoder.encodeInterleaved(data, length / CHANNELS, encoded);
		mBufferOutStream.write(encoded, 0, written);
		return written;
	}

	/**
	 * When recording is finished, close encoder
	 */
	@Override
	protected void onFinishRecording() throws IOException {
		try {
			if (mEncoder != null) {
				final byte[] buffer = new byte[MP3_FLUSH_BUFFER_SIZE];
				final int written = mEncoder.flush(buffer);
				if (written > 0) {
					mBufferOutStream.write(buffer, 0, written);
					mRecordLength += written;
				}
			}
		} finally {
			if (mEncoder != null) {
				mEncoder.close();
				mEncoder = null;
			}
		}
	}

	@Override
	protected void embedMetadata(final RecordingTarget target, final byte[] rfmBytes) throws IOException {
		final File rawMp3 = target.getTemporaryFile();
		final File withTag = File.createTempFile("rfm-mp3-meta-", ".mp3", rawMp3.getParentFile());
		final String title = String.format(Locale.US, "%.1f MHz", getFrequencyKhz() / 1000f);

		try {
			RecordingMetadataIO.prependMp3Id3WithMetadata(rawMp3, withTag, title, getStartedAtMillis(), rfmBytes);
			target.replaceTemporaryFile(withTag);
		} catch (final IOException | RuntimeException error) {
			if (withTag.exists()) {
				//noinspection ResultOfMethodCallIgnored
				withTag.delete();
			}
			throw error;
		}
	}
}
