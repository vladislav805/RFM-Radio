package com.vlad805.fmradio.service.fm.implementation;

import java.util.Locale;

final class TunerCommandFormat {
    private TunerCommandFormat() {
    }

    static String regionName(final int regionId) {
        switch (regionId) {
            case 2:
                return "jp";
            case 3:
                return "jp_wide";
            case 4:
                return "us";
            case 1:
            default:
                return "eu";
        }
    }

    static int spacingKhz(final int spacingId) {
        switch (spacingId) {
            case 1:
                return 50;
            case 3:
                return 200;
            case 2:
            default:
                return 100;
        }
    }

    static String enable(
            final int frequencyKhz,
            final int regionId,
            final int spacingId,
            final boolean stereo,
            final boolean softMute,
            final int antenna,
            final boolean autoAf
    ) {
        return String.format(
                Locale.US,
                "enable freq=%d region=%s spacing=%d stereo=%d soft_mute=%d antenna=%d af=%d",
                frequencyKhz,
                regionName(regionId),
                spacingKhz(spacingId),
                stereo ? 1 : 0,
                softMute ? 1 : 0,
                antenna,
                autoAf ? 1 : 0
        );
    }
}
