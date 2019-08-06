package com.vlad805.fmradio.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import com.vlad805.fmradio.C;

/**
 * vlad805 (c) 2019
 */
@Database(entities = {Station.class, FavoriteStation.class}, version = 1)
@TypeConverters(DateConverter.class)
public abstract class AppDatabase extends RoomDatabase {

	private static AppDatabase sInstance;

	public abstract StationDao stationDao();
	public abstract FavoriteStationDao favoriteStationDao();

	public static AppDatabase getInstance(final Context context) {
		if (sInstance == null) {
			synchronized (AppDatabase.class) {
				if (sInstance == null) {
					sInstance = buildDatabase(context.getApplicationContext());
				}
			}
		}
		return sInstance;
	}

	private static AppDatabase buildDatabase(final Context appContext) {
		return Room.databaseBuilder(appContext, AppDatabase.class, C.DATABASE_NAME)
				.enableMultiInstanceInvalidation()
				.build();
	}


}
