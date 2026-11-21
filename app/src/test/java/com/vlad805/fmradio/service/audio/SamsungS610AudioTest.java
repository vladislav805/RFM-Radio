package com.vlad805.fmradio.service.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SamsungS610AudioTest {
    private static final class PendingRequest {
        final int volume;
        final SamsungS610Audio.HardwareVolumeController.Completion completion;

        PendingRequest(
                final int volume,
                final SamsungS610Audio.HardwareVolumeController.Completion completion
        ) {
            this.volume = volume;
            this.completion = completion;
        }
    }

    @Test
    public void delayedSetVolumeKeepsPreviousAppliedGainUntilConfirmed() {
        final List<PendingRequest> requests = new ArrayList<>();
        final SamsungS610Audio policy = new SamsungS610Audio(
                null,
                (volume, completion) -> requests.add(new PendingRequest(volume, completion))
        );

        policy.applyVolumeIndex(15);
        assertEquals(1, requests.size());
        assertEquals(-1, policy.getAppliedVolume());

        requests.get(0).completion.onApplied(15);
        assertEquals(15, policy.getAppliedVolume());

        policy.applyVolumeIndex(1);
        assertEquals(2, requests.size());
        // Still 15 while set_volume(1) is queued/delayed in native.
        assertEquals(15, policy.getAppliedVolume());

        requests.get(1).completion.onApplied(1);
        assertEquals(1, policy.getAppliedVolume());
    }

    @Test
    public void failedSetVolumePreservesAppliedGainAndAllowsRetryOfSameLevel() {
        final List<PendingRequest> requests = new ArrayList<>();
        final SamsungS610Audio policy = new SamsungS610Audio(
                null,
                (volume, completion) -> requests.add(new PendingRequest(volume, completion))
        );

        policy.applyVolumeIndex(12);
        requests.get(0).completion.onApplied(12);
        assertEquals(12, policy.getAppliedVolume());

        policy.applyVolumeIndex(4);
        assertEquals(2, requests.size());
        requests.get(1).completion.onFailed();
        assertEquals(12, policy.getAppliedVolume());

        // Retry of level 4 must not be suppressed by deduplication after failure.
        policy.applyVolumeIndex(4);
        assertEquals(3, requests.size());
        assertEquals(4, requests.get(2).volume);
        requests.get(2).completion.onApplied(4);
        assertEquals(4, policy.getAppliedVolume());
    }

    @Test
    public void staleCompletionAfterResetDoesNotResurrectAppliedGain() {
        final List<PendingRequest> requests = new ArrayList<>();
        final SamsungS610Audio policy = new SamsungS610Audio(
                null,
                (volume, completion) -> requests.add(new PendingRequest(volume, completion))
        );

        policy.applyVolumeIndex(10);
        assertEquals(1, requests.size());

        policy.resetVolumeState();
        assertEquals(-1, policy.getAppliedVolume());

        // Late onApplied from the pre-reset generation must be ignored.
        requests.get(0).completion.onApplied(10);
        assertEquals(-1, policy.getAppliedVolume());
    }
}
