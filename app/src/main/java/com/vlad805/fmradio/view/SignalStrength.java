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
}
