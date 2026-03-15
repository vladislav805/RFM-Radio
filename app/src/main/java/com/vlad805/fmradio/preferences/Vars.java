package com.vlad805.fmradio.preferences;

import android.util.SparseArray;

/**
 * vlad805 (c) 2021
 */
public class Vars {
    public static final SparseArray<String> sTunerRegions;
    public static final SparseArray<String> sTunerSpacing;

    public static final SparseArray<String> sRecordFormat;

    static {
        sTunerRegions = new SparseArray<>();
        sTunerRegions.put(BandUtils.BAND_EUROPE_US, "Europe/US (87.5-108.0 MHz)");
        sTunerRegions.put(BandUtils.BAND_JAPAN_STANDARD, "Japan Standard (76-95 MHz)");
        sTunerRegions.put(BandUtils.BAND_JAPAN_WIDE, "Japan Wide (76-108 MHz)");

        sTunerSpacing = new SparseArray<>();
        sTunerSpacing.put(BandUtils.SPACING_50kHz, "50 kHz");
        sTunerSpacing.put(BandUtils.SPACING_100kHz, "100 kHz");
        sTunerSpacing.put(BandUtils.SPACING_200kHz, "200 kHz");

        sRecordFormat = new SparseArray<>();
        sRecordFormat.put(0, "WAV PCM 16bit (raw, large size)");
        sRecordFormat.put(1, "MP3 192kbps (compressed, small size)");
    }
}
