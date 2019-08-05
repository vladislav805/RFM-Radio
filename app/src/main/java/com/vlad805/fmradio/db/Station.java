package com.vlad805.fmradio.db;

import android.os.Parcel;
import android.os.Parcelable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

/**
 * vlad805 (c) 2019
 */
@Entity(tableName = "station")
public class Station implements Parcelable, IStation {

	@PrimaryKey
	private int frequency;

	@ColumnInfo(name = "title")
	private String title;

	@ColumnInfo(name = "old")
	private boolean old = false;


	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(this.frequency);
		dest.writeString(this.title);
	}

	@Ignore
	public Station(int frequency) {
		this(frequency, null);
	}


	public Station(int frequency, String title) {
		this.frequency = frequency;
		this.title = title;
	}

	@Ignore
	protected Station(Parcel in) {
		this.frequency = in.readInt();
		this.title = in.readString();
	}

	@Override
	public int getFrequency() {
		return frequency;
	}

	@Override
	public String getTitle() {
		return title;
	}

	public boolean isOld() {
		return old;
	}

	public void setOld(boolean old) {
		this.old = old;
	}

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public static final Parcelable.Creator<Station> CREATOR = new Parcelable.Creator<Station>() {
		@Override
		public Station createFromParcel(Parcel source) {
			return new Station(source);
		}

		@Override
		public Station[] newArray(int size) {
			return new Station[size];
		}
	};
}
