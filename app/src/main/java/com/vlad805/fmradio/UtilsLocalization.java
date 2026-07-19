package com.vlad805.fmradio;

import com.vlad805.fmradio.preferences.BandUtils;

public class UtilsLocalization {
    private static final String[] RDS_PROGRAM_TYPES = new String[] {
            "", // 0
            "News", // 1
            "Current affairs", // 2
            "Information", // 3
            "Sport", // 4
            "Education", // 5
            "Drama", // 6
            "Culture", // 7
            "Science", // 8
            "Varied", // 9
            "Popular Music (Pop)", // 10
            "Rock Music", // 11
            "Easy Listening", // 12
            "Light Classical", // 13
            "Serious Classical", // 14
            "Other Music", // 15
            "Weather", // 16
            "Finance", // 17
            "Children's Programmes", // 18
            "Social Affairs", // 19
            "Religion", // 20
            "Phone-in", // 21
            "Travel", // 22
            "Leisure", // 23
            "Jazz Music", // 24
            "Country Music", // 25
            "National Music", // 26
            "Oldies Music", // 27
            "Folk Music", // 28
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
            "Rhythm and Blues", // 16
            "Soft Rhythm and Blues", // 17
            "Foreign Language", // 18
            "Religious Music", // 19
            "Religious Talk", // 20
            "Personality", // 21
            "Public", // 22
            "College", // 23
            "Spanish Talk", // 24
            "Spanish Music", // 25
            "Hip Hop", // 26
            "Unassigned", // 27
            "Unassigned", // 28
            "Weather", // 29
            "Emergency Test", // 30
            "Emergency", // 31
    };

    public static String getProgramType(final int pty, final int region) {
        final String[] names = region == BandUtils.BAND_US
                ? RBDS_PROGRAM_TYPES
                : RDS_PROGRAM_TYPES;

        return pty > 0 && pty < names.length ? names[pty] : names[0];

    }
}
