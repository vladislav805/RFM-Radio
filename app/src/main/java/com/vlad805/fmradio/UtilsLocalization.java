package com.vlad805.fmradio;

import com.vlad805.fmradio.preferences.BandUtils;

import java.util.Locale;

public class UtilsLocalization {
    private static final String[] RDS_PROGRAM_TYPES = new String[] {
            "", // 0
            "News", // 1
            "Affairs", // 2
            "Information", // 3
            "Sport", // 4
            "Education", // 5
            "Drama", // 6
            "Culture", // 7
            "Science", // 8
            "Varied", // 9
            "Pop", // 10
            "Rock", // 11
            "Easy", // 12
            "Light Classic", // 13
            "Classical", // 14
            "Other Music", // 15
            "Weather", // 16
            "Finance", // 17
            "Children", // 18
            "Social", // 19
            "Religion", // 20
            "Phone-in", // 21
            "Travel", // 22
            "Leisure", // 23
            "Jazz", // 24
            "Country", // 25
            "National", // 26
            "Oldies", // 27
            "Folk", // 28
            "Documentary", // 29
            "Alarm Test", // 30
            "Alarm", // 31
    };

    private static final String[] RBDS_PROGRAM_TYPES = new String[] {
            "", // 0
            "News", // 1
            "Information", // 2
            "Sports", // 3
            "Talk", // 4
            "Rock", // 5
            "Classic Rock", // 6
            "Adult Hits", // 7
            "Soft Rock", // 8
            "Top 40", // 9
            "Country", // 10
            "Oldies", // 11
            "Soft", // 12
            "Nostalgia", // 13
            "Jazz", // 14
            "Classical", // 15
            "R&B", // 16
            "Soft R&B", // 17
            "Foreign", // 18
            "Relig. Music", // 19
            "Relig. Talk", // 20
            "Personality", // 21
            "Public", // 22
            "College", // 23
            "Spanish Talk", // 24
            "Latin", // 25
            "Hip Hop", // 26
            "Unassigned", // 27
            "Unassigned", // 28
            "Weather", // 29
            "Emerg. Test", // 30
            "Emergency", // 31
    };

    public static String getProgramType(final int pty, final int region) {
        final String[] names = region == BandUtils.BAND_US
                ? RBDS_PROGRAM_TYPES
                : RDS_PROGRAM_TYPES;

        return pty > 0 && pty < names.length ? names[pty] : names[0];

    }

    public static String formatRdsCountry(final String country) {
        if (country == null) {
            return "";
        }

        final String upper = country.trim().toUpperCase(Locale.ROOT);
        if (upper.length() != 2) {
            return country;
        }

        final char first = upper.charAt(0);
        final char second = upper.charAt(1);
        if (first < 'A' || first > 'Z' || second < 'A' || second > 'Z') {
            return country;
        }

        // Unicode regional indicators start at U+1F1E6 for A.
        final int regionalIndicatorA = 0x1F1E6;

        return new StringBuilder(7)
                .append(upper)
                .append('\u00A0')
                .appendCodePoint(regionalIndicatorA + (first - 'A'))
                .appendCodePoint(regionalIndicatorA + (second - 'A'))
                .toString();
    }
}
