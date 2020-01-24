package com.vlad805.fmradio.fm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * vlad805 (c) 2019
 */
public class FMState implements Parcelable {
	private int state = STATE_OFF;
	private String message;
	private int frequency;
	private int rssi;
	private String ps;
	private String rt;

	public static final int STATE_OFF = 0;
	public static final int STATE_ON = 1;

	public FMState() { }

	public int getState() {
		return state;
	}

	public void setState(int state) {
		this.state = state;
	}

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public int getFrequency() {
		return frequency;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	public int getRssi() {
		return rssi;
	}

	public void setRssi(int rssi) {
		this.rssi = rssi;
	}

	public String getPs() {
		return ps;
	}

	public void setPs(String ps) {
		this.ps = ps;
	}

	public String getRt() {
		return rt;
	}

	public void setRt(String rt) {
		this.rt = rt;
	}

	protected FMState(Parcel in) {
		state = in.readInt();
		message = in.readString();
		frequency = in.readInt();
		rssi = in.readInt();
		ps = in.readString();
		rt = in.readString();
	}

	@Override
	public String toString() {
		return "FMState {state=" + state + ", frequency=" + frequency + ", rssi=" + rssi + ", ps='" + ps + '\'' + ", rt='" + rt + '\'' + "message='" + message + "'}";
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(state);
		dest.writeString(message);
		dest.writeInt(frequency);
		dest.writeInt(rssi);
		dest.writeString(ps);
		dest.writeString(rt);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final Creator<FMState> CREATOR = new Creator<FMState>() {
		@Override
		public FMState createFromParcel(Parcel in) {
			return new FMState(in);
		}

		@Override
		public FMState[] newArray(int size) {
			return new FMState[size];
		}
	};
}