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
	private final int mKHz;

	private State mState = State.IDLE;
	private File mRecordFile = null;
	private int mRecordLength = 0;

	private BufferedOutputStream mBufferOutStream = null;
	private FileOutputStream mFileOutStream = null;

	private final int RECORD_BUFFER_SIZE = 1048576;

	private long mStarted;
	private long mLast;
	private static final int DELTA = 750;

	public FMRecordService(final Context context, final int kHz) {
		mContext = context;
		mKHz = kHz;
	}

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

			long now = System.currentTimeMillis();
			if (now - mLast > DELTA) {
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

	private void updateState(final String event) {
		mContext.sendBroadcast(new Intent(event)
				.putExtra(C.Key.SIZE, mRecordLength)
				.putExtra(C.Key.DURATION, getDuration())
				.putExtra(C.Key.PATH, mRecordFile.getAbsolutePath())
		);
	}

	private int getDuration() {
		return (int) (System.currentTimeMillis() - mStarted) / 1000;
	}

	/***************************/

	private void createFile() {
		final File dir = makeDirectoryHierarchy();
		final String name = getFilename();

		mRecordFile = new File(dir, name);

		try {
			mRecordFile.createNewFile();

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

	public static byte[] stringToByteArray(final String s) {
		char[] buffer = s.toCharArray();
		byte[] content = new byte[buffer.length];
		for (int i = 0; i < content.length; i++) {
			content[i] = (byte) buffer[i];
		}
		return content;
	}

	private void writeFinal() {
		try {
			if (mRecordFile != null) {
				RandomAccessFile file = new RandomAccessFile(mRecordFile, "rw");

				byte[] buffer = new byte[4];
				writeBytes(buffer, 0, 4, mRecordLength + 36);
				file.seek(4);
				file.write(buffer);
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
