package com.vlad805.fmradio.service.audio;

import com.vlad805.fmradio.enums.TunerDriver;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AudioLifecycleTest {
    @Test
    public void heliumStartsBeforeEnableDoesNotRestartOnEnabledAndStopsBeforeDisable() {
        final List<String> events = new ArrayList<>();
        final AudioLifecycle lifecycle = new AudioLifecycle(
                AudioSelection.resolve(TunerDriver.HAL_HELIUM, 31),
                () -> events.add("startAudio"),
                () -> events.add("stopAudio")
        );

        lifecycle.beforeEnable();
        events.add("enable");
        lifecycle.onEnabled();
        lifecycle.beforeDisable();
        events.add("disable");
        lifecycle.stop();

        assertEquals(
                Arrays.asList("startAudio", "enable", "stopAudio", "disable"),
                events
        );
    }

    @Test
    public void v4l2StartsOnEnabledAndStopsOnTerminalStop() {
        final List<String> events = new ArrayList<>();
        final AudioLifecycle lifecycle = new AudioLifecycle(
                AudioSelection.resolve(TunerDriver.V4L2_QCOM, 31),
                () -> events.add("startAudio"),
                () -> events.add("stopAudio")
        );

        lifecycle.beforeEnable();
        events.add("enable");
        lifecycle.onEnabled();
        lifecycle.beforeDisable();
        events.add("disable");
        lifecycle.stop();

        assertEquals(
                Arrays.asList("enable", "startAudio", "disable", "stopAudio"),
                events
        );
    }

    @Test
    public void failureOrTeardownReleasesPrestartedAudioOnceAndIgnoresLateEnabled() {
        final List<String> events = new ArrayList<>();
        final AudioLifecycle lifecycle = new AudioLifecycle(
                AudioSelection.resolve(TunerDriver.HAL_HELIUM, 31),
                () -> events.add("startAudio"),
                () -> events.add("stopAudio")
        );

        lifecycle.beforeEnable();
        lifecycle.stop();
        lifecycle.stop();
        lifecycle.onEnabled();

        assertEquals(Arrays.asList("startAudio", "stopAudio"), events);
    }

    @Test
    public void noneSelectionInvokesNeitherCallback() {
        final List<String> events = new ArrayList<>();
        final AudioLifecycle lifecycle = new AudioLifecycle(
                AudioSelection.resolve(TunerDriver.NONE, 31),
                () -> events.add("startAudio"),
                () -> events.add("stopAudio")
        );

        lifecycle.beforeEnable();
        lifecycle.onEnabled();
        lifecycle.beforeDisable();
        lifecycle.stop();

        assertEquals(Collections.emptyList(), events);
    }
}
