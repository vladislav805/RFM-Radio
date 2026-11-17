package com.vlad805.fmradio.preferences;

/**
 * vlad805 (c) 2021
 */
public class BandUtils {
    public static class BandLimit {
        public final int lower;
        public final int upper;

        public BandLimit(final int lower, final int upper) {
            this.lower = lower;
            this.upper = upper;
        }
    }

    public static final int BAND_EUROPE_US = 1;
    public static final int BAND_JAPAN_STANDARD = 2;
    public static final int BAND_JAPAN_WIDE = 3;
    public static final int BAND_MOD = 4;

    public static final int SPACING_10kHz = 1;
    public static final int SPACING_50kHz = 2;
    public static final int SPACING_100kHz = 3;
    public static final int SPACING_200kHz = 4;

    private static final BandLimit LIMIT_EU = new BandLimit(87500, 108000);
    private static final BandLimit LIMIT_JP_STD = new BandLimit(76000, 95000);
    private static final BandLimit LIMIT_JP_WIDE = new BandLimit(76000, 108000);
    private static final BandLimit LIMIT_MOD = new BandLimit(30000, 999000);

    public static BandLimit getBandLimit(final int type) {
        switch (type) {
            case BAND_JAPAN_STANDARD: {
                return LIMIT_JP_STD;
            }

            case BAND_JAPAN_WIDE: {
                return LIMIT_JP_WIDE;
            }

            case BAND_MOD: {
                return LIMIT_EU;
            }
            
            case BAND_EUROPE_US:
            default: {
                return LIMIT_EU;
            }
        }
    }

    public static int getSpacing(final int type) {
        switch (type) {
            case SPACING_10kHz: return 10;
            case SPACING_50kHz: return 50;
            case SPACING_200kHz: return 200;
            case SPACING_100kHz: default: return 100;
        }
    }
}
