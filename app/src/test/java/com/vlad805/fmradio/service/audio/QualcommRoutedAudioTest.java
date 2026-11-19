package com.vlad805.fmradio.service.audio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class QualcommRoutedAudioTest {
    private static final int DEVICE_OUT_FM = 0x100000;
    private static final int DEVICE_OUT_SPEAKER = 0x2;
    private static final int DEVICE_OUT_WIRED_HEADSET = 0x4;

    private static final class FakeSinks {
        final List<String> events = new ArrayList<>();
        String fmStatus = "fm_status=0";
        boolean forceSpeakerState = false;
        float gain = 0.5f;

        final QualcommRoutedAudio.ParameterSink parameters = new QualcommRoutedAudio.ParameterSink() {
            @Override
            public String getParameters(final String keys) {
                events.add("get:" + keys);
                return fmStatus;
            }

            @Override
            public void setParameters(final String keyValuePairs) {
                events.add("param:" + keyValuePairs);
            }
        };

        final QualcommRoutedAudio.FrameworkRouteSink frameworkRoute =
                output -> events.add("framework:" + output);

        final QualcommRoutedAudio.ForceUseSink forceUse = new QualcommRoutedAudio.ForceUseSink() {
            @Override
            public boolean isForceSpeakerNow() {
                return forceSpeakerState;
            }

            @Override
            public void setForceSpeaker(final boolean enabled) {
                forceSpeakerState = enabled;
                events.add("forceUse:" + enabled);
            }
        };

        final QualcommRoutedAudio.VolumeGainProvider volumeGain = device -> {
            events.add("gain:" + device);
            return gain;
        };

        final Runnable refreshRoute = () -> events.add("slimbusRefresh");
    }

    @Test
    public void enableRouteResetsActiveFmStatusAndPreservesParameterOrder() {
        final FakeSinks sinks = new FakeSinks();
        sinks.fmStatus = "fm_status=1";
        sinks.gain = 0.75f;

        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                sinks.parameters,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                sinks.refreshRoute,
                false,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        policy.applyRoute(true, AudioOutput.HEADPHONES, true);

        final int headsetFmRoute = DEVICE_OUT_WIRED_HEADSET | DEVICE_OUT_FM;
        assertEquals(
                Arrays.asList(
                        "framework:HEADPHONES",
                        "get:fm_status",
                        "param:fm_routing=" + headsetFmRoute,
                        "gain:4",
                        "param:fm_volume=0.75",
                        "param:fm_mute=0",
                        "param:fm_routing=" + headsetFmRoute,
                        "param:handle_fm=" + headsetFmRoute
                ),
                sinks.events
        );
    }

    @Test
    public void v4l2RoutedSetsForMediaAndNeverInvokesSlimbus() {
        final FakeSinks sinks = new FakeSinks();
        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                sinks.parameters,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                () -> {},
                true,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        assertEquals(AudioOutput.HEADPHONES, policy.getOutput(AudioOutput.SPEAKER));

        policy.applyRoute(true, AudioOutput.SPEAKER, false);
        policy.afterOutputChanged(AudioOutput.SPEAKER);

        assertEquals(AudioOutput.SPEAKER, policy.getOutput(AudioOutput.HEADPHONES));
        final int speakerFmRoute = DEVICE_OUT_SPEAKER | DEVICE_OUT_FM;
        assertEquals(
                Arrays.asList(
                        "framework:SPEAKER",
                        "get:fm_status",
                        "param:fm_mute=0",
                        "param:fm_routing=" + speakerFmRoute,
                        "param:handle_fm=" + speakerFmRoute,
                        "forceUse:true"
                ),
                sinks.events
        );
    }

    @Test
    public void heliumRoutedAppliesRouteThenInvokesSingleSlimbusRefreshWithoutForMedia() {
        final FakeSinks sinks = new FakeSinks();
        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                sinks.parameters,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                sinks.refreshRoute,
                false,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        assertEquals(AudioOutput.HEADPHONES, policy.getOutput(AudioOutput.HEADPHONES));
        assertEquals(AudioOutput.SPEAKER, policy.getOutput(AudioOutput.SPEAKER));

        policy.applyRoute(true, AudioOutput.SPEAKER, false);
        policy.afterOutputChanged(AudioOutput.SPEAKER);

        final int speakerFmRoute = DEVICE_OUT_SPEAKER | DEVICE_OUT_FM;
        assertEquals(
                Arrays.asList(
                        "framework:SPEAKER",
                        "get:fm_status",
                        "param:fm_mute=0",
                        "param:fm_routing=" + speakerFmRoute,
                        "param:handle_fm=" + speakerFmRoute,
                        "slimbusRefresh"
                ),
                sinks.events
        );
        assertFalse(sinks.forceSpeakerState);
    }

    @Test
    public void disableRouteClearsFmBitAndPreservesDeviceMask() {
        final FakeSinks sinks = new FakeSinks();
        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                sinks.parameters,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                sinks.refreshRoute,
                false,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        policy.applyRoute(false, AudioOutput.SPEAKER, false);

        assertEquals(
                Arrays.asList(
                        "framework:SPEAKER",
                        "param:fm_routing=" + DEVICE_OUT_SPEAKER,
                        "param:handle_fm=" + DEVICE_OUT_SPEAKER
                ),
                sinks.events
        );
    }

    @Test
    public void loopbackPathRetainsV4l2ForceUseBehaviorWithoutSlimbus() {
        final FakeSinks sinks = new FakeSinks();
        final LightAudioService loopback = new LightAudioService(
                null,
                true,
                () -> {},
                sinks.forceUse
        );

        assertEquals(AudioOutput.HEADPHONES, loopback.getOutput());
        loopback.setOutput(AudioOutput.SPEAKER);
        assertEquals(AudioOutput.SPEAKER, loopback.getOutput());
        loopback.setOutput(AudioOutput.HEADPHONES);
        assertEquals(AudioOutput.HEADPHONES, loopback.getOutput());

        assertEquals(Arrays.asList("forceUse:true", "forceUse:false"), sinks.events);
    }

    @Test
    public void volumeChangeRefreshesFmVolumeForActiveOutputDevice() {
        final FakeSinks sinks = new FakeSinks();
        sinks.gain = 0.25f;
        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                sinks.parameters,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                sinks.refreshRoute,
                false,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        policy.onMusicVolumeChanged(AudioOutput.SPEAKER);
        assertEquals(Arrays.asList("gain:2", "param:fm_volume=0.25"), sinks.events);

        sinks.events.clear();
        policy.onMusicVolumeChanged(AudioOutput.HEADPHONES);
        assertEquals(Arrays.asList("gain:4", "param:fm_volume=0.25"), sinks.events);
    }

    @Test
    public void nullParameterSinkIsSafeNoOp() {
        final FakeSinks sinks = new FakeSinks();
        final QualcommRoutedAudio policy = new QualcommRoutedAudio(
                null,
                sinks.frameworkRoute,
                sinks.forceUse,
                sinks.volumeGain,
                sinks.refreshRoute,
                false,
                DEVICE_OUT_FM,
                DEVICE_OUT_SPEAKER,
                DEVICE_OUT_WIRED_HEADSET
        );

        policy.applyRoute(true, AudioOutput.HEADPHONES, true);
        policy.onMusicVolumeChanged(AudioOutput.HEADPHONES);

        assertEquals(Collections.emptyList(), sinks.events);
    }
}
