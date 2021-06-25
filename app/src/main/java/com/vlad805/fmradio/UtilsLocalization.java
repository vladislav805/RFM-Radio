package com.vlad805.fmradio;

import android.util.Log;

/**
 * vlad805 (c) 2021
 */
public class UtilsLocalization {

    /**
     * Only Europe
     * @link https://www.electronics-notes.com/articles/audio-video/broadcast-audio/rds-radio-data-system-pty-codes.php
     */
    private static final String[] mProgramTypeName = new String[] {
            "N/A", // 0
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

    public static String getProgramType(final int pty) {
        // If in range of names
        return pty > 0 && pty < mProgramTypeName.length
                // Return name
                ? mProgramTypeName[pty]
                // Else return N/A
                : mProgramTypeName[0];

    }
}
