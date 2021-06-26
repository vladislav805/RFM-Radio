package com.vlad805.fmradio.preferences;

import android.util.SparseArray;
import com.vlad805.fmradio.BuildConfig;

import static com.vlad805.fmradio.service.fm.implementation.AbstractFMController.*;

/**
 * vlad805 (c) 2021
 */
public class Vars {
    public static final SparseArray<String> sTunerDrivers;
    public static final SparseArray<String> sTunerRegions;
    public static final SparseArray<String> sTunerSpacing;

    static {
        sTunerDrivers = new SparseArray<>();
        sTunerDrivers.put(DRIVER_QUALCOMM, "QualComm V4L2 service");
        sTunerDrivers.put(DRIVER_SPIRIT3, "QualComm V4L2 service from Spirit3 (installed spirit3 required)");

        if (BuildConfig.DEBUG) {
            sTunerDrivers.put(DRIVER_EMPTY, "Test service (empty)");
        }

        sTunerRegions = new SparseArray<>();
        sTunerRegions.put(BandUtils.BAND_EUROPE_US, "Europe/US (87.5-108.0 MHz)");
        sTunerRegions.put(BandUtils.BAND_JAPAN_STANDARD, "Japan Standard (76-95 MHz)");
        sTunerRegions.put(BandUtils.BAND_JAPAN_WIDE, "Japan Wide (76-108 MHz)");

        sTunerSpacing = new SparseArray<>();
        sTunerSpacing.put(BandUtils.SPACING_50kHz, "50 kHz");
        sTunerSpacing.put(BandUtils.SPACING_100kHz, "100 kHz");
        sTunerSpacing.put(BandUtils.SPACING_200kHz, "200 kHz");
    }
}
