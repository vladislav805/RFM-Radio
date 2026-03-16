package com.vlad805.fmradio.helper;

import com.vlad805.fmradio.enums.TunerDriver;

import java.io.File;

public final class TunerDriverDetector {
    private TunerDriverDetector() {}

    public static TunerDriver getTunerDriver() {
        if (hasDevRadio0()) {
            return TunerDriver.LEGACY;
        }

        if (hasFmHeliumLibrary()) {
            return TunerDriver.HAL;
        }

        return TunerDriver.NONE;
    }

    public static boolean isDeviceSupported() {
        return getTunerDriver() != TunerDriver.NONE;
    }

    public static boolean hasDevRadio0() {
        return new File("/dev/radio0").exists();
    }

    private static final String[] FM_HELIUM_PATHS = {
            "/vendor/lib64/fm_helium.so",
            "/vendor/lib/fm_helium.so",
            "/system/vendor/lib64/fm_helium.so",
            "/system/vendor/lib/fm_helium.so",
            "/system_ext/lib64/fm_helium.so",
            "/system_ext/lib/fm_helium.so",
            "/odm/lib64/fm_helium.so",
            "/odm/lib/fm_helium.so"
    };

    private static boolean hasFmHeliumLibrary() {
        for (final String path : FM_HELIUM_PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }

        return false;
    }
}
