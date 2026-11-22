package com.vlad805.fmradio.enums;

public enum TunerDriver {
    V4L2_QCOM(true, true, true, true, true, true, 95000),
    V4L2_EXYNOS(false, false, false, false, false, false, 90000),
    HAL_HELIUM(true, true, true, true, true, true, 95000),
    NONE(false, false, false, false, false, false, 95000);

    private final boolean mSupportsSoftMute;
    private final boolean mSupportsNotchFilter;
    private final boolean mSupportsAntenna;
    private final boolean mSupportsAutoAf;
    private final boolean mSupportsJapanWide;
    private final boolean mSupportsStereoDetection;
    private final int mJapanStandardUpperKhz;

    TunerDriver(
            final boolean supportsSoftMute,
            final boolean supportsNotchFilter,
            final boolean supportsAntenna,
            final boolean supportsAutoAf,
            final boolean supportsJapanWide,
            final boolean supportsStereoDetection,
            final int japanStandardUpperKhz
    ) {
        mSupportsSoftMute = supportsSoftMute;
        mSupportsNotchFilter = supportsNotchFilter;
        mSupportsAntenna = supportsAntenna;
        mSupportsAutoAf = supportsAutoAf;
        mSupportsJapanWide = supportsJapanWide;
        mSupportsStereoDetection = supportsStereoDetection;
        mJapanStandardUpperKhz = japanStandardUpperKhz;
    }

    public boolean supportsSoftMute() {
        return mSupportsSoftMute;
    }

    public boolean supportsNotchFilter() {
        return mSupportsNotchFilter;
    }

    public boolean supportsAntenna() {
        return mSupportsAntenna;
    }

    public boolean supportsAutoAf() {
        return mSupportsAutoAf;
    }

    public boolean supportsJapanWide() {
        return mSupportsJapanWide;
    }

    public boolean supportsStereoDetection() {
        return mSupportsStereoDetection;
    }

    public int getJapanStandardUpperKhz() {
        return mJapanStandardUpperKhz;
    }
}
