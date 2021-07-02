package com.vlad805.fmradio.preferences;

import android.util.SparseArray;
import com.vlad805.fmradio.BuildConfig;

import static com.vlad805.fmradio.service.audio.AudioService.SERVICE_LIGHT;
import static com.vlad805.fmradio.service.audio.AudioService.SERVICE_SPIRIT3;
import static com.vlad805.fmradio.service.fm.implementation.AbstractFMController.*;

/**
 * vlad805 (c) 2021
 */
public class Vars {
    public static final SparseArray<String> sTunerDrivers;
    public static final SparseArray<String> sTunerRegions;
    public static final SparseArray<String> sTunerSpacing;

    public static final SparseArray<String> sAudioSource;
    public static final SparseArray<String> sRecordFormat;
    public static final SparseArray<String> sAudioService;


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

        sAudioSource = new SparseArray<>();
        sAudioSource.put(0, "0, DEFAULT");
        sAudioSource.put(1, "1, MIC");
        sAudioSource.put(2, "2, VOICE_UPLINK");
        sAudioSource.put(3, "3, VOICE_DOWNLINK");
        sAudioSource.put(4, "6, VOICE_CALL");
        sAudioSource.put(5, "5, CAMCORDER");
        sAudioSource.put(6, "6, VOICE_RECOGNITION");
        sAudioSource.put(7, "7, VOICE_COMMUNICATION");
        sAudioSource.put(8, "8, REMOTE_SUBMIX");
        sAudioSource.put(1998, "1998, FM");

        sRecordFormat = new SparseArray<>();
        sRecordFormat.put(0, "WAV PCM 16bit (raw, large size)");
        sRecordFormat.put(1, "MP3 192kbps (compressed, small size)");

        sAudioService = new SparseArray<>();
        sAudioService.put(SERVICE_LIGHT, "Lightweight audio service");
        sAudioService.put(SERVICE_SPIRIT3, "Audio service from Spirit3");
    }
}
