package com.vlad805.fmradio.db;

import androidx.room.TypeConverter;

import java.util.Date;

/**
 * vlad805 (c) 2019
 */
public class DateConverter {
	@TypeConverter
	public static Date toDate(Long timestamp) {
		return timestamp == null ? null : new Date(timestamp);
	}

	@TypeConverter
	public static Long toTimestamp(Date date) {
		return date == null ? null : date.getTime();
	}
}