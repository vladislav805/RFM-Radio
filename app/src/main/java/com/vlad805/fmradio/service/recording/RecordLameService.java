package com.vlad805.fmradio.service.recording;

import android.content.Context;
import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

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
	private AndroidLame mLame;

	/**
	 * @param context Context
	 * @param kHz Current frequency in kHz
	 */
	public RecordLameService(final Context context, final int kHz, final int sampleRate) {
		super(context, kHz, sampleRate);
	}

	@Override
	protected String getExtension() {
		return "mp3";
	}

	/**
	 * When recording is being started, need create instance of encoder lame
	 */
	@Override
	protected void onFileCreated() throws IOException {
		writeId3v23Tag();
		mLame = new LameBuilder()
				.setInSampleRate(getSampleRate())
				.setOutChannels(2)
				.setOutBitrate(192)
				.setOutSampleRate(getSampleRate())
				.setMode(LameBuilder.Mode.JSTEREO)
				.setQuality(8)
				.build();
	}

	/**
	 * When data is received, encode chunk of data with lame
	 * @param data New data
	 * @param length Length of data
	 * @param encoded Pointer to byte array of encoded data (output)
	 * @return Length of encoded data
	 */
	@Override
	protected int onReceivedData(short[] data, int length, byte[] encoded) {
		return mLame.encodeBufferInterLeaved(data, length / 2, encoded);
	}

	/**
	 * When recording is finished, close encoder
	 */
	@Override
	protected void onFinishRecording() {
		try {
			/**
			 * @see {@link https://github.com/vladislav805/RFM-Radio/issues/82}
			 */
			Thread.sleep(500);
			mLame.close();
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
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
