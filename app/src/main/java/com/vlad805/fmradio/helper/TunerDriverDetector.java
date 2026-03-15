package com.vlad805.fmradio.helper;

import com.vlad805.fmradio.enums.TunerDriver;
import com.vlad805.fmradio.service.fm.implementation.QualcommHal;
import com.vlad805.fmradio.service.fm.implementation.QualcommLegacy;

public final class TunerDriverDetector {
    private TunerDriverDetector() {}

    public static TunerDriver getTunerDriver() {
        if (QualcommLegacy.isAbleToWork()) {
            return TunerDriver.LEGACY;
        }

        if (QualcommHal.isAbleToWork()) {
            return TunerDriver.HAL;
        }

        return TunerDriver.NONE;
    }

    public static boolean isDeviceSupported() {
        return getTunerDriver() != TunerDriver.NONE;
    }
}
