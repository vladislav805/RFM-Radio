package com.vlad805.fmradio.enums;

import com.vlad805.fmradio.preferences.BandUtils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TunerDriverCapabilitiesTest {
    @Test
    public void qualcommBackendsSupportFullFeatureSet() {
        for (final TunerDriver driver : new TunerDriver[]{TunerDriver.V4L2_QCOM, TunerDriver.HAL_HELIUM}) {
            assertTrue(driver.hasKnownCapabilities());
            assertTrue(driver.supportsSoftMute());
            assertTrue(driver.supportsNotchFilter());
            assertTrue(driver.supportsAntenna());
            assertTrue(driver.supportsAutoAf());
            assertTrue(driver.supportsJapanWide());
            assertTrue(driver.supportsStereoDetection());
            assertEquals(95000, driver.getJapanStandardUpperKhz());
        }
    }

    @Test
    public void exynosBackendDisablesUnsupportedFeaturesAndUsesNarrowJapanBand() {
        final TunerDriver driver = TunerDriver.V4L2_EXYNOS;
        assertTrue(driver.hasKnownCapabilities());
        assertFalse(driver.supportsSoftMute());
        assertFalse(driver.supportsNotchFilter());
        assertFalse(driver.supportsAntenna());
        assertFalse(driver.supportsAutoAf());
        assertFalse(driver.supportsJapanWide());
        assertFalse(driver.supportsStereoDetection());
        assertEquals(90000, driver.getJapanStandardUpperKhz());
    }

    @Test
    public void noneBackendHasUnknownCapabilitiesSoUiDoesNotGateFeatures() {
        final TunerDriver driver = TunerDriver.NONE;
        assertFalse(driver.hasKnownCapabilities());
        assertFalse(driver.supportsSoftMute());
        assertFalse(driver.supportsNotchFilter());
        assertFalse(driver.supportsAntenna());
        assertFalse(driver.supportsAutoAf());
        assertFalse(driver.supportsJapanWide());
        assertFalse(driver.supportsStereoDetection());
        assertEquals(95000, driver.getJapanStandardUpperKhz());

        // Guarded UI predicates must not treat unresolved NONE as unsupported.
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsSoftMute());
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsNotchFilter());
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsAntenna());
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsAutoAf());
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsJapanWide());
        assertFalse(driver.hasKnownCapabilities() && !driver.supportsStereoDetection());
    }

    @Test
    public void bandLimitsFollowDriverCapabilities() {
        // Exynos clamps both Japan Standard and Japan Wide to 76-90 MHz.
        final BandUtils.BandLimit exynosJpStd = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_STANDARD, TunerDriver.V4L2_EXYNOS);
        assertEquals(76000, exynosJpStd.lower);
        assertEquals(90000, exynosJpStd.upper);

        final BandUtils.BandLimit exynosJpWide = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_WIDE, TunerDriver.V4L2_EXYNOS);
        assertEquals(76000, exynosJpWide.lower);
        assertEquals(90000, exynosJpWide.upper);

        final BandUtils.BandLimit exynosEu = BandUtils.getBandLimit(BandUtils.BAND_EUROPE, TunerDriver.V4L2_EXYNOS);
        assertEquals(87500, exynosEu.lower);
        assertEquals(108000, exynosEu.upper);

        // Qualcomm / Helium support full Japan Standard (76-95) and Japan Wide (76-108).
        for (final TunerDriver driver : new TunerDriver[]{TunerDriver.V4L2_QCOM, TunerDriver.HAL_HELIUM}) {
            final BandUtils.BandLimit jpStd = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_STANDARD, driver);
            assertEquals(76000, jpStd.lower);
            assertEquals(95000, jpStd.upper);

            final BandUtils.BandLimit jpWide = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_WIDE, driver);
            assertEquals(76000, jpWide.lower);
            assertEquals(108000, jpWide.upper);
        }

        // Unspecified / NONE driver preserves standard band definitions.
        final BandUtils.BandLimit defaultJpStd = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_STANDARD);
        assertEquals(76000, defaultJpStd.lower);
        assertEquals(95000, defaultJpStd.upper);

        final BandUtils.BandLimit defaultJpWide = BandUtils.getBandLimit(BandUtils.BAND_JAPAN_WIDE, TunerDriver.NONE);
        assertEquals(76000, defaultJpWide.lower);
        assertEquals(108000, defaultJpWide.upper);
    }

    @Test
    public void spacingMappingReturnsExpectedKhz() {
        assertEquals(50, BandUtils.getSpacing(BandUtils.SPACING_50kHz));
        assertEquals(100, BandUtils.getSpacing(BandUtils.SPACING_100kHz));
        assertEquals(200, BandUtils.getSpacing(BandUtils.SPACING_200kHz));
        assertEquals(100, BandUtils.getSpacing(999));
    }
}
