package com.vlad805.fmradio.preferences;

import net.grandcentrix.tray.AppPreferences;

/**
 * vlad805 (c) 2021
 */
public class LaunchCounter {
    private static final String KEY_LAUNCH_COUNT = "launch_count";
    private static final String KEY_NEVER_SHOW = "donation_never_show";

    private static final int EVERY_TH_LAUNCH = 15;

    private static int getLaunchCount(final AppPreferences prefs) {
        return prefs.getInt(KEY_LAUNCH_COUNT, 0);
    }

    public static void setDonationNeverShow(final AppPreferences prefs) {
        prefs.put(KEY_NEVER_SHOW, true);
    }

    /**
     * Determine, need to show donation window?
     * @param prefs Preferences object
     * @return true, if need to show donation window
     */
    public static boolean checkForDonation(final AppPreferences prefs) {
        // Current count of launches
        final int launches = incrementLaunch(prefs);

        // If user prefer never see this window
        if (prefs.contains(KEY_NEVER_SHOW)) {
            return false;
        }

        // If it's 15, 30, 45 time, we need to show donation window
        return (launches % EVERY_TH_LAUNCH) == 0;
    }

    private static int incrementLaunch(final AppPreferences prefs) {
        final int launches = getLaunchCount(prefs) + 1;

        prefs.put(KEY_LAUNCH_COUNT, launches);

        return launches;
    }
}
