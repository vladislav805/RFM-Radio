package com.vlad805.fmradio.helper;

import com.vlad805.fmradio.enums.TunerDriver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TunerDriverDetectorTest {
    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();
    @Test
    public void allowsQualcommV4l2DriverFamilies() {
        assertTrue(TunerDriverDetector.isQualcommV4l2DriverName("radio-iris"));
        assertTrue(TunerDriverDetector.isQualcommV4l2DriverName("radio-tavarua\n"));
        assertTrue(TunerDriverDetector.isQualcommV4l2DriverName("RADIO-IRIS"));

        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.classifyTunerDriver("radio-iris", true, false)
        );
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.classifyTunerDriver("radio-tavarua", true, true)
        );
    }

    @Test
    public void allowsSamsungS610DriverName() {
        assertTrue(TunerDriverDetector.isSamsungS610DriverName("s610-radio"));
        assertTrue(TunerDriverDetector.isSamsungS610DriverName("S610-RADIO\n"));
        assertFalse(TunerDriverDetector.isSamsungS610DriverName("radio-iris"));
        assertFalse(TunerDriverDetector.isSamsungS610DriverName(""));
        assertFalse(TunerDriverDetector.isSamsungS610DriverName(null));

        assertEquals(
                TunerDriver.V4L2_EXYNOS,
                TunerDriverDetector.classifyTunerDriver("s610-radio", true, false)
        );
        // The V4L2 node wins over a present HAL library.
        assertEquals(
                TunerDriver.V4L2_EXYNOS,
                TunerDriverDetector.classifyTunerDriver("s610-radio", true, true)
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.classifyTunerDriver("s610-radio", false, false)
        );
        assertEquals(
                TunerDriver.HAL_HELIUM,
                TunerDriverDetector.classifyTunerDriver("s610-radio", false, true)
        );
    }

    @Test
    public void rejectsUnknownAndEmptyV4l2Names() {
        assertFalse(TunerDriverDetector.isQualcommV4l2DriverName("s610"));
        assertFalse(TunerDriverDetector.isQualcommV4l2DriverName("si4709"));
        assertFalse(TunerDriverDetector.isQualcommV4l2DriverName(""));
        assertFalse(TunerDriverDetector.isQualcommV4l2DriverName("   "));
        assertFalse(TunerDriverDetector.isQualcommV4l2DriverName(null));

        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.classifyTunerDriver("si4709", true, false)
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.classifyTunerDriver("", true, false)
        );

        assertEquals(
                TunerDriver.HAL_HELIUM,
                TunerDriverDetector.classifyTunerDriver("si4709", true, true)
        );
    }

    @Test
    public void usesLegacyFallbackOnlyWhenSysfsNameIsAbsentOrUnreadable() {
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.classifyTunerDriver(null, true, false)
        );
        assertEquals(
                TunerDriver.HAL_HELIUM,
                TunerDriverDetector.classifyTunerDriver(null, false, true)
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.classifyTunerDriver(null, false, false)
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.classifyTunerDriver("radio-iris", false, false)
        );
    }

    @Test
    public void fallsBackToLegacyWhenSysfsReadFailsOrNodeIsMissing() throws IOException {
        final File missingFile = new File(tempFolder.getRoot(), "missing");
        assertNull(TunerDriverDetector.readV4l2DriverName(missingFile));

        // Passing a directory causes FileReader to throw IOException (simulating unreadable sysfs).
        final File unreadableDir = tempFolder.newFolder("unreadable");
        final String fallbackName = TunerDriverDetector.readV4l2DriverName(unreadableDir);
        assertNull(fallbackName);
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.classifyTunerDriver(fallbackName, true, false)
        );

        final File s610File = tempFolder.newFile("name");
        Files.write(s610File.toPath(), "s610\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("s610", TunerDriverDetector.readV4l2DriverName(s610File));
    }

    @Test
    public void mapsNativeHandshakeBackendToken() {
        assertEquals(TunerDriver.V4L2_QCOM, TunerDriverDetector.fromNativeBackend("v4l2_qcom"));
        assertEquals(TunerDriver.V4L2_EXYNOS, TunerDriverDetector.fromNativeBackend("v4l2_exynos"));
        assertEquals(TunerDriver.HAL_HELIUM, TunerDriverDetector.fromNativeBackend("hal_helium"));
        assertNull(TunerDriverDetector.fromNativeBackend("none"));
        assertNull(TunerDriverDetector.fromNativeBackend(null));
        assertNull(TunerDriverDetector.fromNativeBackend(""));
        assertNull(TunerDriverDetector.fromNativeBackend("unknown"));
    }

    @Test
    public void resolvesNativeBackendFromProvisionalGuess() {
        assertEquals(
                TunerDriver.HAL_HELIUM,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.V4L2_QCOM, "hal_helium")
        );
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.HAL_HELIUM, "v4l2_qcom")
        );
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.V4L2_QCOM, null)
        );
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.V4L2_QCOM, "")
        );
        assertEquals(
                TunerDriver.V4L2_QCOM,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.V4L2_QCOM, "unknown_backend")
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.resolveNativeBackend(TunerDriver.V4L2_QCOM, "none")
        );
        assertEquals(
                TunerDriver.NONE,
                TunerDriverDetector.resolveNativeBackend(null, null)
        );
    }
}
