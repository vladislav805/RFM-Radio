package com.vlad805.fmradio.helper;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * vlad805 (c) 2020
 */
public final class RecordSchemaHelper {
	public static String prepareString(String schema, final int kHz) {
		final Calendar cal = Calendar.getInstance();

		final Map<Character, Integer> assoc = new HashMap<>();
		assoc.put('f', kHz);
		assoc.put('y', cal.get(Calendar.YEAR));
		assoc.put('m', cal.get(Calendar.MONTH) + 1);
		assoc.put('d', cal.get(Calendar.DAY_OF_MONTH));
		assoc.put('h', cal.get(Calendar.HOUR_OF_DAY));
		assoc.put('i', cal.get(Calendar.MINUTE));
		assoc.put('s', cal.get(Calendar.SECOND));
		assoc.put('r', (int) (Math.random() * 89999) + 10000);

		for (final Character c : assoc.keySet()) {
			final String pat = "$" + c;

			schema = Pattern.compile(
					Pattern.quote(pat),
					Pattern.CASE_INSENSITIVE
			).matcher(schema).replaceAll(
					String.format(Locale.US, "%02d", assoc.get(c))
			);
		}

		return schema;
	}

}
