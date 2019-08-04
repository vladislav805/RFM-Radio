package com.vlad805.fmradio;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * vlad805 (c) 2019
 */
public class FMEvent implements Parcelable {

	public static final int EVENT_SET_FREQUENCY = 1;
	public static final int EVENT_SEEK_ENDED = 2;

	private int mEvent;
	private int mIntData;
	private String mStringData;

	public FMEvent(int event, int intData) {
		mEvent = event;
		mIntData = intData;
	}

	public FMEvent(int event, String strData) {
		mEvent = event;
		mStringData = strData;
	}

	protected FMEvent(Parcel in) {
		mEvent = in.readInt();
		mIntData = in.readInt();
		mStringData = in.readString();
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(mEvent);
		dest.writeInt(mIntData);
		dest.writeString(mStringData);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	public static final Creator<FMEvent> CREATOR = new Creator<FMEvent>() {
		@Override
		public FMEvent createFromParcel(Parcel in) {
			return new FMEvent(in);
		}

		@Override
		public FMEvent[] newArray(int size) {
			return new FMEvent[size];
		}
	};

	public int getEvent() {
		return mEvent;
	}

	public int getIntData() {
		return mIntData;
	}

	public String getStringData() {
		return mStringData;
	}
}
