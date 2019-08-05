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
@Entity(tableName = "favorite_station")
public class FavoriteStation implements Parcelable, IStation {

	@PrimaryKey
	private int frequency;

	@ColumnInfo(name = "title")
	private String title;

	@ColumnInfo(name = "order")
	private int order;

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
	public FavoriteStation(int frequency) {
		this(frequency, null);
	}

	public FavoriteStation(int frequency, String title) {
		this.frequency = frequency;
		this.title = title;
	}

	@Ignore
	protected FavoriteStation(Parcel in) {
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

	public void setFrequency(int frequency) {
		this.frequency = frequency;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public static final Parcelable.Creator<FavoriteStation> CREATOR = new Parcelable.Creator<FavoriteStation>() {
		@Override
		public FavoriteStation createFromParcel(Parcel source) {
			return new FavoriteStation(source);
		}

		@Override
		public FavoriteStation[] newArray(int size) {
			return new FavoriteStation[size];
		}
	};
}
