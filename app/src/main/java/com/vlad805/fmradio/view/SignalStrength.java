package com.vlad805.fmradio.view;

public final class SignalStrength {
    private SignalStrength() {
    }

    public static int levelForRmssi(final int rmssi) {
        if (rmssi < -105) {
            return 0;
        }

        if (rmssi <= -91) {
            return 1;
        }

        if (rmssi <= -81) {
            return 2;
        }

        if (rmssi <= -71) {
            return 3;
        }

        if (rmssi <= -61) {
            return 4;
        }

        return 5;
    }

    public static int levelForSinr(final int sinr) {
        if (sinr <= -5) {
            return 0;
        }

        if (sinr <= 0) {
            return 1;
        }

        if (sinr <= 5) {
            return 2;
        }

        if (sinr <= 10) {
            return 3;
        }

        if (sinr <= 15) {
            return 4;
        }

        return 5;
    }

    public static int levelForMetrics(final int rmssi, final int sinr) {
        final boolean hasRmssi = rmssi != Integer.MIN_VALUE;
        final boolean hasSinr = sinr != Integer.MIN_VALUE;

        if (hasRmssi && hasSinr) {
            return Math.min(levelForRmssi(rmssi), levelForSinr(sinr));
        }

        if (hasSinr) {
            return levelForSinr(sinr);
        }

        if (hasRmssi) {
            return levelForRmssi(rmssi);
        }

        return 0;
    }
}
