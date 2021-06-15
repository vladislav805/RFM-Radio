package com.vlad805.fmradio.service.recording;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import com.vlad805.fmradio.C;
import com.vlad805.fmradio.R;
import com.vlad805.fmradio.Storage;
import com.vlad805.fmradio.helper.RecordSchemaHelper;
import com.vlad805.fmradio.service.fm.RecordError;

import java.io.*;

/**
 * Abstract recorder
 * vlad805 (c) 2021
 */
public abstract class RecordService implements IFMRecorder {
    public enum State {
        IDLE,
        RECORDING,
        FINISHING,
        DONE
    }

    /**
     * Minimal delay for update
     */
    private static final int MIN_DELAY_PING = 750;

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
    protected File mRecordFile = null;

    /**
     * Size of file in bytes
     */
    protected int mRecordLength = 0;

    /**
     * Writable stream
     */
    protected BufferedOutputStream mBufferOutStream = null;
    private FileOutputStream mFileOutStream = null;

    /**
     * Timestamp of start recording
     */
    private long mStarted;

    /**
     * Last broadcasting
     */
    private long mLast;

    /**
     * Constructor
     * @param context Context
     * @param kHz Current frequency in kHz
     */
    public RecordService(final Context context, final int kHz) {
        mContext = context;
        mKHz = kHz;
    }

    /**
     * Calls when user click "Start recording"
     * @throws RecordError If something going wrong
     */
    @Override
    public final void startRecord() throws RecordError {
        createFile();
        mState = State.RECORDING;
        mContext.sendBroadcast(new Intent(C.Event.RECORD_STARTED));
        mStarted = System.currentTimeMillis();
    }

    @Override
    public final void record(final short[] data, final int length) {
        if (mState == State.RECORDING) {
            try {
                byte[] bufferEncoded = new byte[(int) (7200 + data.length * 2 * 1.75)];
                int bytesEncoded = onReceivedData(data, length, bufferEncoded);
                mBufferOutStream.write(bufferEncoded, 0, bytesEncoded);
                mRecordLength += bytesEncoded;
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

    /**
     * Called on user click "Stop record"
     */
    @Override
    public void stopRecord() {
        if (mState == State.FINISHING || mState == State.DONE) {
            return;
        }

        mState = State.FINISHING;

        onFinishRecording();

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
     * Returns state
     * @return State
     */
    public State getState() {
        return mState;
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
    private void createFile() throws RecordError {
        final File dir = makeDirectoryHierarchy();
        final String name = getFilename();

        mRecordFile = new File(dir, name);

        try {
            if (mRecordFile.exists()) {
                throw new RecordError("File with these name already exists!\nPlease, check filename schema for unique.");
            }

            if (!mRecordFile.createNewFile()) {
                throw new FileNotFoundException();
            }

            mFileOutStream = new FileOutputStream(mRecordFile, true);
            mBufferOutStream = new BufferedOutputStream(mFileOutStream, 131072);

            onFileCreated();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns user preferred path or default
     * @return Path schema to directory
     */
    private String getPreferredDirectory() {
        return Storage.getPrefString(mContext, C.PrefKey.RECORDING_DIRECTORY, mContext.getString(R.string.pref_recording_path_value));
    }

    /**
     * Returns user preferred name or default
     * @return Filename schema
     */
    private String getPreferredFilename() {
        return Storage.getPrefString(mContext, C.PrefKey.RECORDING_FILENAME, mContext.getString(R.string.pref_recording_name_value));
    }

    /**
     * Create directory hierarchy
     * @return Directory
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private File makeDirectoryHierarchy() {
        final String path = Environment.getExternalStorageDirectory() + File.separator + getPreferredDirectory();
        final File dir = new File(RecordSchemaHelper.prepareString(path, mKHz));

        if (!dir.exists()) {
            dir.mkdirs();
        }

        return dir;
    }

    /**
     * Returns filename for audio file
     * @return Filename
     */
    private String getFilename() {
        return RecordSchemaHelper.prepareString(getPreferredFilename(), mKHz) + "." + getExtension();
    }

    /**
     * Return file extension
     * @return Extension without ".": for example "mp3" or "wav".
     */
    protected abstract String getExtension();

    /**
     * Calls when file created and opened streams for write.
     */
    protected abstract void onFileCreated();

    /**
     * Calls when AudioRecord receive new packet of data
     * @param data New data
     * @param length Length of data
     * @param encoded Pointer to byte array of encoded data (output)
     * @return Length of payload encoded array
     * @throws IOException If happens error
     */
    protected abstract int onReceivedData(final short[] data, final int length, final byte[] encoded) throws IOException;

    /**
     * Calls when recording is finished.
     */
    protected abstract void onFinishRecording();
}
