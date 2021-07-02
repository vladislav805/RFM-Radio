package com.vlad805.fmradio.controller;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * vlad805 (c) 2021
 */
public final class TunerState implements Parcelable {
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

    // Last action
    private String lastAction;

    // Stereo - is stereo audio?
    private boolean stereo;

    public TunerState() {

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

    public String getLastAction() {
        return lastAction;
    }

    void setLastAction(final String lastAction) {
        this.lastAction = lastAction;
    }

    public boolean isStereo() {
        return stereo;
    }

    void setStereo(final boolean stereo) {
        this.stereo = stereo;
    }

    protected TunerState(final Parcel in) {
        status = (TunerStatus) in.readValue(TunerStatus.class.getClassLoader());
        frequency = in.readInt();
        pi = in.readInt();
        pty = in.readInt();
        ps = in.readString();
        rt = in.readString();
        rssi = in.readInt();
        lastAction = in.readString();
        stereo = in.readInt() > 0;
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
        dest.writeString(lastAction);
        dest.writeInt(stereo ? 1 : 0);
    }

    public static final Parcelable.Creator<TunerState> CREATOR = new Parcelable.Creator<TunerState>() {
        @Override
        public TunerState createFromParcel(Parcel in) {
            return new TunerState(in);
        }

        @Override
        public TunerState[] newArray(int size) {
            return new TunerState[size];
        }
    };

    @Override
    public String toString() {
        return "TunerState{" +
                "status=" + status +
                ", frequency=" + frequency +
                ", pi=" + pi +
                ", pty=" + pty +
                ", ps='" + ps + '\'' +
                ", rt='" + rt + '\'' +
                ", rssi=" + rssi +
                '}';
    }
}
