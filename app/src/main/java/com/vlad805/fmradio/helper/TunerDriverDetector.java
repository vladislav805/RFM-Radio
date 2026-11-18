package com.vlad805.fmradio.helper;

import com.vlad805.fmradio.enums.TunerDriver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;

public final class TunerDriverDetector {
    private static final String SYSFS_RADIO0_NAME_PATH = "/sys/class/video4linux/radio0/name";

    private TunerDriverDetector() {}

    public static TunerDriver getTunerDriver() {
        return classifyTunerDriver(readV4l2DriverName(), hasDevRadio0(), hasFmHeliumLibrary());
    }

    public static boolean isDeviceSupported() {
        return getTunerDriver() != TunerDriver.NONE;
    }

    public static boolean hasDevRadio0() {
        return new File("/dev/radio0").exists();
    }

    /**
     * Allowlist check mirroring native detect_backend_kind(): only Qualcomm
     * V4L2 driver families ("iris" / "tavarua") are treated as compatible.
     */
    public static boolean isQualcommV4l2DriverName(final String name) {
        if (name == null) {
            return false;
        }
        final String normalized = name.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.contains("iris") || normalized.contains("tavarua");
    }

    /** Matches Samsung Exynos S610 FM driver name ("s610-radio"), mirroring native. */
    public static boolean isSamsungS610DriverName(final String name) {
        if (name == null) {
            return false;
        }
        return name.trim().toLowerCase(Locale.US).contains("s610");
    }

    /**
     * Maps the native ready handshake "backend" token to the active TunerDriver,
     * returning null if absent, "none", or unrecognized so callers keep fallback.
     */
    public static TunerDriver fromNativeBackend(final String backend) {
        if (backend == null) {
            return null;
        }
        switch (backend.trim().toLowerCase(Locale.US)) {
            case "v4l2_qcom":
                return TunerDriver.V4L2_QCOM;
            case "v4l2_exynos":
                return TunerDriver.V4L2_EXYNOS;
            case "hal_helium":
                return TunerDriver.HAL_HELIUM;
            default:
                return null;
        }
    }

    /**
     * Classifies the active backend from optional sysfs driver name, /dev/radio0
     * presence, and fm_helium.so presence. Null v4l2DriverName represents an absent
     * or unreadable sysfs node (e.g. under SELinux in the unprivileged app process).
     */
    static TunerDriver classifyTunerDriver(
            final String v4l2DriverName,
            final boolean hasDevRadio0,
            final boolean hasFmHeliumLibrary
    ) {
        if (v4l2DriverName != null) {
            if (hasDevRadio0 && isQualcommV4l2DriverName(v4l2DriverName)) {
                return TunerDriver.V4L2_QCOM;
            }
            if (hasDevRadio0 && isSamsungS610DriverName(v4l2DriverName)) {
                return TunerDriver.V4L2_EXYNOS;
            }
            return hasFmHeliumLibrary ? TunerDriver.HAL_HELIUM : TunerDriver.NONE;
        }

        if (hasDevRadio0) {
            return TunerDriver.V4L2_QCOM;
        }

        return hasFmHeliumLibrary ? TunerDriver.HAL_HELIUM : TunerDriver.NONE;
    }

    private static String readV4l2DriverName() {
        return readV4l2DriverName(new File(SYSFS_RADIO0_NAME_PATH));
    }

    static String readV4l2DriverName(final File sysfsFile) {
        try {
            if (!sysfsFile.exists()) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(sysfsFile))) {
                final String line = reader.readLine();
                return line != null ? line.trim() : "";
            }
        } catch (IOException | SecurityException ignored) {
            // SELinux may deny sysfs access to the unprivileged app process; treat
            // read/access failure as absent so the root native helper decides.
            return null;
        }
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
