package com.vlad805.fmradio.service.recording;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
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
			final int bitrateKbps
	) {
		super(context, kHz, sampleRate);
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
		writeId3v23Tag();
		mEncoder = new LameMp3Encoder(getSampleRate(), CHANNELS, mBitrateKbps, QUALITY);
	}

	/**
	 * When data is received, encode chunk of data with lame
	 * @param data New data
	 * @param length Length of data
	 * @param encoded Pointer to byte array of encoded data (output)
	 * @return Length of encoded data
	 */
	@Override
	protected int onReceivedData(short[] data, int length, byte[] encoded) throws IOException {
		return mEncoder.encodeInterleaved(data, length / 2, encoded);
	}

	/**
	 * When recording is finished, close encoder
	 */
	@Override
	protected void onFinishRecording() {
		try {
			if (mEncoder != null) {
				final byte[] buffer = new byte[MP3_FLUSH_BUFFER_SIZE];
				final int written = mEncoder.flush(buffer);
				if (written > 0) {
					mBufferOutStream.write(buffer, 0, written);
					mRecordLength += written;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			if (mEncoder != null) {
				mEncoder.close();
				mEncoder = null;
			}
		}
	}

	private void writeId3v23Tag() throws IOException {
		final ByteArrayOutputStream frames = new ByteArrayOutputStream();
		final String title = String.format(Locale.US, "%.1f MHz", getFrequencyKhz() / 1000f);
		final String recordedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
				.format(new Date(getStartedAtMillis()));

		writeTextFrame(frames, "TIT2", title);
		writeTextFrame(frames, "TDRC", recordedAt);
		writeTextFrame(frames, "TENC", "RFM Radio");

		final byte[] framesData = frames.toByteArray();
		final byte[] header = new byte[] {
				'I', 'D', '3',
				3, 0,
				0,
				toSynchsafe((framesData.length >> 21) & 0x7F),
				toSynchsafe((framesData.length >> 14) & 0x7F),
				toSynchsafe((framesData.length >> 7) & 0x7F),
				toSynchsafe(framesData.length & 0x7F),
		};

		mBufferOutStream.write(header);
		mBufferOutStream.write(framesData);
	}

	private void writeTextFrame(final ByteArrayOutputStream out, final String id, final String value) throws IOException {
		writeFrame(out, id, withEncodingPrefix(value));
	}

	private byte[] withEncodingPrefix(final String value) {
		final byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
		final byte[] payload = new byte[text.length + 1];
		payload[0] = 0;
		System.arraycopy(text, 0, payload, 1, text.length);
		return payload;
	}

	private void writeFrame(final ByteArrayOutputStream out, final String id, final byte[] payload) throws IOException {
		out.write(id.getBytes(StandardCharsets.ISO_8859_1));
		out.write(new byte[] {
				(byte) ((payload.length >> 24) & 0xFF),
				(byte) ((payload.length >> 16) & 0xFF),
				(byte) ((payload.length >> 8) & 0xFF),
				(byte) (payload.length & 0xFF),
				0,
				0,
		});
		out.write(payload);
	}

	private byte toSynchsafe(final int value) {
		return (byte) (value & 0x7F);
	}
}
