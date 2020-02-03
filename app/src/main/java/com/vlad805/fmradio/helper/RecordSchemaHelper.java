package com.vlad805.fmradio.helper;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vlad805 (c) 2020
 */
public final class RecordSchemaHelper {
	private static final Pattern REGEXP = Pattern.compile("\\$([ymdhisf])", Pattern.CASE_INSENSITIVE);

	public static String prepareString(String schema, final int kHz) {
		final Matcher res = REGEXP.matcher(schema);

		final Calendar cal = Calendar.getInstance();

		final Map<Character, Integer> assoc = new HashMap<>();
		assoc.put('f', kHz);
		assoc.put('y', cal.get(Calendar.YEAR));
		assoc.put('m', cal.get(Calendar.MONTH) + 1);
		assoc.put('d', cal.get(Calendar.DAY_OF_MONTH));
		assoc.put('h', cal.get(Calendar.HOUR_OF_DAY));
		assoc.put('i', cal.get(Calendar.MINUTE));
		assoc.put('s', cal.get(Calendar.SECOND));

		while (res.find()) {
			final char symbol = schema.charAt(res.start() + 1);
			if (assoc.containsKey(symbol)) {
				schema = schema.replaceAll(String.valueOf(symbol), String.valueOf(assoc.get(symbol)));
			}
		}

		return schema;
	}

}
