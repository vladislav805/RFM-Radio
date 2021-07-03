package com.vlad805.fmradio.controller;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that stores the current state of the radio tuner, audio service and other
 * parameters.
 * The main storage is located in the FMService, but on demand it can be transferred
 * to an Activity, Fragment or other Service using Parcelable.
 * Changing an instance of this class can only be done using the class RadioStateUpdater.
 * vlad805 (c) 2021
 */
public final class RadioState implements Parcelable {
    // State of tuner
    private TunerStatus status = TunerStatus.IDLE;

    // Frequency in kHz
    private int frequency = 0;

    // PI - Program ID
    private int pi;

    // PTY - Program TYpe
    private int pty;

    // PS - Program Service
    private String ps = "";

    // RT - Radio Text
    private String rt = "";

    // RSSI - dB
    private int rssi;

    // Stereo - is stereo audio?
    private boolean stereo;

    // Is recording started
    private boolean recording = false;

    // Date in unixtime of start of recording
    private long recordingStarted = -1L;

    // Outputting sound from speakers
    private boolean forceSpeaker = false;

    public RadioState() {

    }

    public TunerStatus getStatus() {
        return status;
    }

    void setStatus(final TunerStatus status) {
        this.status = status;
    }

    public int getFrequency() {
        return frequency;
    }

    void setFrequency(final int frequency) {
        this.frequency = frequency;
    }

    public int getPi() {
        return pi;
    }

    void setPi(final int pi) {
        this.pi = pi;
    }

    public int getPty() {
        return pty;
    }

    void setPty(final int pty) {
        this.pty = pty;
    }

    public String getPs() {
        return ps;
    }

    void setPs(final String ps) {
        this.ps = ps;
    }

    public String getRt() {
        return rt;
    }

    void setRt(final String rt) {
        this.rt = rt;
    }

    public int getRssi() {
        return rssi;
    }

    void setRssi(final int rssi) {
        this.rssi = rssi;
    }

    public boolean isStereo() {
        return stereo;
    }

    void setStereo(final boolean stereo) {
        this.stereo = stereo;
    }

    public boolean isRecording() {
        return recording;
    }

    void setRecording(final boolean recording) {
        this.recording = recording;
    }

    public long getRecordingDuration() {
        return recording ? (System.currentTimeMillis() - recordingStarted) / 1000 : -1L;
    }

    public long getRecordingStarted() {
        return recordingStarted;
    }

    void setRecordingStarted(long recordingStarted) {
        this.recordingStarted = recordingStarted;
    }

    public boolean isForceSpeaker() {
        return forceSpeaker;
    }

    void setForceSpeaker(boolean forceSpeaker) {
        this.forceSpeaker = forceSpeaker;
    }

    protected RadioState(final Parcel in) {
        status = (TunerStatus) in.readValue(TunerStatus.class.getClassLoader());
        frequency = in.readInt();
        pi = in.readInt();
        pty = in.readInt();
        ps = in.readString();
        rt = in.readString();
        rssi = in.readInt();
        stereo = in.readInt() > 0;
        recording = in.readInt() > 0;
        recordingStarted = in.readLong();
        forceSpeaker = in.readInt() > 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, int flags) {
        dest.writeValue(status);
        dest.writeInt(frequency);
        dest.writeInt(pi);
        dest.writeInt(pty);
        dest.writeString(ps);
        dest.writeString(rt);
        dest.writeInt(rssi);
        dest.writeInt(stereo ? 1 : 0);
        dest.writeInt(recording ? 1 : 0);
        dest.writeLong(recordingStarted);
        dest.writeInt(forceSpeaker ? 1 : 0);
    }

    public static final Parcelable.Creator<RadioState> CREATOR = new Parcelable.Creator<RadioState>() {
        @Override
        public RadioState createFromParcel(Parcel in) {
            return new RadioState(in);
        }

        @Override
        public RadioState[] newArray(int size) {
            return new RadioState[size];
        }
    };

    @Override
    public String toString() {
        return "TunerState{" +
                status +
                ", kHz=" + frequency +
                ", pi=" + pi +
                ", pty=" + pty +
                // ", ps='" + ps + '\'' +
                // ", rt='" + rt + '\'' +
                ", rssi=" + rssi +
                ", rec_st=" + recording +
                ", rec_be=" + recordingStarted +
                '}';
    }
}
