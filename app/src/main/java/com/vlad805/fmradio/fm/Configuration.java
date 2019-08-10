package com.vlad805.fmradio.fm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * vlad805 (c) 2019
 */
public class Configuration implements Parcelable {

	private int frequency;

	private String ps;
	private String rt;

	public Configuration() { }

	public int getFrequency() {
		return frequency;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
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

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(this.frequency);
		dest.writeString(this.ps);
		dest.writeString(this.rt);
	}

	protected Configuration(Parcel in) {
		this.frequency = in.readInt();
		this.ps = in.readString();
		this.rt = in.readString();
	}

	public static final Creator<Configuration> CREATOR = new Creator<Configuration>() {
		@Override
		public Configuration createFromParcel(Parcel source) {
			return new Configuration(source);
		}

		@Override
		public Configuration[] newArray(int size) {
			return new Configuration[size];
		}
	};
}