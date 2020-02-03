package com.vlad805.fmradio.service;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.service.fm.IFMRecorder;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * vlad805 (c) 2020
 */
public class FMRecordService implements IFMRecorder {
	public enum State {
		IDLE,
		RECORDING,
		FINISHING,
		DONE
	}

	private final Context mContext;

	/**
	 * Current frequency
	 */
	private final int mKHz;

	/**
	 * State of recording
	 */
	private State mState = State.IDLE;

	/**
	 * Recordable file
	 */
	private File mRecordFile = null;

	/**
	 * Size of file in bytes
	 */
	private int mRecordLength = 0;

	/**
	 * Writable stream
	 */
	private BufferedOutputStream mBufferOutStream = null;
	private FileOutputStream mFileOutStream = null;

	private final int RECORD_BUFFER_SIZE = 1048576;

	/**
	 * Timestamp of start recording
	 */
	private long mStarted;

	/**
	 * Last broadcasting
	 */
	private long mLast;

	/**
	 * Minimal delay for
	 */
	private static final int MIN_DELAY_PING = 750;

	/**
	 * Constructor
	 * @param context Context
	 * @param kHz Current frequency in kHz
	 */
	public FMRecordService(final Context context, final int kHz) {
		mContext = context;
		mKHz = kHz;
	}

	/**
	 * Returns state
	 * @return State
	 */
	public State getState() {
		return mState;
	}

	@Override
	public void startRecord() {
		createFile();
		mState = State.RECORDING;
		mContext.sendBroadcast(new Intent(C.Event.RECORD_STARTED));
		mStarted = System.currentTimeMillis();
	}

	@Override
	public void record(final byte[] data, final int length) {
		if (mState == State.RECORDING) {
			try {
				mBufferOutStream.write(data, 0, length);
				mRecordLength += length;
			} catch (IOException e) {
				e.printStackTrace();
				mState = State.IDLE;
			}

			final long now = System.currentTimeMillis();
			if (now - mLast > MIN_DELAY_PING) {
				mLast = now;
				updateState(C.Event.RECORD_TIME_UPDATE);
			}

		}
	}

	@Override
	public void stopRecord() {
		if (mState == State.FINISHING || mState == State.DONE) {
			return;
		}

		mState = State.FINISHING;

		writeFinal();

		try {
			if (mBufferOutStream != null) {
				mBufferOutStream.close();
				mBufferOutStream = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			if (mFileOutStream != null) {
				mFileOutStream.close();
				mFileOutStream = null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		updateState(C.Event.RECORD_ENDED);
	}

	/**
	 * Send event. Also send size of file, duration and path
	 * @param event Key of event
	 */
	private void updateState(final String event) {
		mContext.sendBroadcast(new Intent(event)
				.putExtra(C.Key.SIZE, mRecordLength)
				.putExtra(C.Key.DURATION, getDuration())
				.putExtra(C.Key.PATH, mRecordFile.getAbsolutePath())
		);
	}

	/**
	 * Returns current duration of record
	 * @return Duration in seconds
	 */
	private int getDuration() {
		return (int) (System.currentTimeMillis() - mStarted) / 1000;
	}

	/**
	 * Create file and initialize streams
	 */
	private void createFile() {
		final File dir = makeDirectoryHierarchy();
		final String name = getFilename();

		mRecordFile = new File(dir, name);

		try {
			if (!mRecordFile.createNewFile()) {
				throw new FileNotFoundException();
			}

			mFileOutStream = new FileOutputStream(mRecordFile, true);
			mBufferOutStream = new BufferedOutputStream(mFileOutStream, 131072);

			writeWavHeader();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Create directory hierarchy
	 * @return Directory
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private File makeDirectoryHierarchy() {
		final String path = Environment.getExternalStorageDirectory() + File.separator + "RFM" + File.separator + "record" + File.separator + getTodayDirectoryName();
		final File dir = new File(path);

		if (!dir.exists()) {
			dir.mkdirs();
		}

		return dir;
	}

	/**
	 * Returns today directory
	 * @return Name of directory
	 */
	private String getTodayDirectoryName() {
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
		sdf.setTimeZone(TimeZone.getDefault());
		return sdf.format(new Date());
	}



	/**
	 * Returns filename for audio file
	 * @return Filename
	 */
	private String getFilename() {
		Date now = new Date();
		SimpleDateFormat sdf = new SimpleDateFormat("HHmmss", Locale.getDefault());
		sdf.setTimeZone(TimeZone.getDefault());
		return String.format(Locale.ENGLISH, "FM-%s-%04d.wav", sdf.format(now), mKHz);
	}

	/**
	 * Write bytes to buffer
	 * @param buffer Buffer
	 * @param index Offset
	 * @param bytes Length
	 * @param value Value
	 */
	private void writeBytes(byte[] buffer, int index, int bytes, int value) {
		if (bytes > 0) {
			buffer[index] = (byte) (value & 0xff);
		}
		if (bytes > 1) {
			buffer[index + 1] = (byte) ((value >> 8) & 0xff);
		}
		if (bytes > 2) {
			buffer[index + 2] = (byte) ((value >> 16) & 0xff);
		}
		if (bytes > 3) {
			buffer[index + 3] = (byte) ((value >> 24) & 0xff);
		}
	}

	/**
	 * Write WAV header data
	 *  0… 3 (4 bytes) chunkId
	 *                 ASCII symbols «RIFF» = 0x52494646. Beginning of RIFF-chunk.
	 *  4… 7 (4 bytes) chunkSize
	 *                 This is the remaining chain size starting at this position.
	 *                 In other words, this is the fileSize-8, that is, the chunkId
	 *                 and chunkSize fields are excluded.
	 *  8…11 (4 bytes) format
	 *                 Contains «WAVE» = 0x57415645
	 * 12…15 (4 bytes) subchunk1Id
	 * 	               Contains "fmt " = 0x666d7420
	 * 16…19 (4 bytes) subchunk1Size
	 * 	               16 for PCM format. This is the remaining size of the chain,
	 * 	               starting from this position.
	 * 20…21 (2 bytes) audioFormat
	 *                Audio format, list of acceptable formats.
	 * 	               For PCM = 1 (i.e., linear quantization).
	 * 	               Values other than 1 indicate some compression format.
	 * 22…23 (2 bytes) numChannels
	 * 	               The number of channels. Mono = 1, Stereo = 2, etc.
	 * 24…27 (4 bytes) sampleRate
	 * 	               Sampling rate. 8000 Hz, 44100 Hz, etc.
	 * 28…31 (4 bytes) byteRate
	 * 	               The number of bytes transferred per second of playback.
	 * 32…33 (2 bytes) blockAlign
	 * 	               The number of bytes for one sample, including all channels.
	 * 34…35 (2 bytes) bitsPerSample
	 *                 The number of bits in the sample. The so-called "depth" or
	 *                 sound accuracy. 8 bits, 16 bits, etc.
	 * 36…39 (4 bytes) subchunk2Id
	 * 	               Contains «data» = 0x64617461
	 * 40…43 (4 bytes) subchunk2Size
	 *                 The number of bytes in the data area.
	 * 44…   (N bytes) data
	 * 	               WAV data
	 */
	private void writeWavHeader() {
		byte[] header = stringToByteArray("RIFF....WAVEfmt sc1safncsamrbytrbabsdatasc2s");

		writeBytes(header, 4, 4, mRecordLength + 36);
		writeBytes(header, 16, 4, 16);
		writeBytes(header, 20, 2, 1);
		writeBytes(header, 22, 2, 2); // channels
		writeBytes(header, 24, 4, 44100); // sample rate
		writeBytes(header, 28, 4, (44100 * 2) * 2); // (sample rate * channels) * 2
		writeBytes(header, 32, 2, 2 * 2); // channels * 2
		writeBytes(header, 34, 2, 16);
		writeBytes(header, 40, 4, mRecordLength);

		try {
			mBufferOutStream.write(header);
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns string as byte array
	 * @param s Input string
	 * @return Byte array
	 */
	public static byte[] stringToByteArray(final String s) {
		char[] buffer = s.toCharArray();
		byte[] content = new byte[buffer.length];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) buffer[i];
		}
		return content;
	}

	/**
	 * Update header WAV
	 */
	private void writeFinal() {
		try {
			if (mRecordFile != null) {
				final RandomAccessFile file = new RandomAccessFile(mRecordFile, "rw");

				final byte[] buffer = new byte[4];

				// Set 4-7 bytes (length + 36)
				writeBytes(buffer, 0, 4, mRecordLength + 36);
				file.seek(4);
				file.write(buffer);

				// Set 40-43 bytes (length)
				writeBytes(buffer, 0, 4, mRecordLength);
				file.seek(40);
				file.write(buffer);
				file.close();
			}
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}
}
