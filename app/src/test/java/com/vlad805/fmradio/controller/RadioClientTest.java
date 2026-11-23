package com.vlad805.fmradio.controller;

import android.content.ServiceConnection;

import androidx.annotation.NonNull;

import com.vlad805.fmradio.enums.Direction;
import com.vlad805.fmradio.enums.PowerMode;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RadioClientTest {
    private FakeServiceBinder binder;
    private FakeController controller;

    @Before
    public void setUp() {
        binder = new FakeServiceBinder();
        controller = new FakeController();
        controller.state.applyFrequency(101700);
        controller.state.applyStatus(TunerStatus.ENABLED);
    }

    @Test
    public void queuesCommandsBeforeConnectionAndExecutesInFifoOrderOnConnect() {
        final List<Integer> receivedMasks = new ArrayList<>();
        final List<RadioState> receivedStates = new ArrayList<>();
        final StateListener stateListener = (state, mask) -> {
            receivedStates.add(state);
            receivedMasks.add(mask);
        };
        final EventListener eventListener = new EventListener() {};

        final RadioClient client = new RadioClient(binder, 87500, stateListener, eventListener);
        assertFalse(client.isConnected());
        assertEquals(87500, client.getState().getFrequency());
        assertEquals(TunerStatus.IDLE, client.getState().getStatus());

        client.connect();
        assertEquals(1, binder.bindCount);

        // Issue commands before onServiceConnected fires
        client.enable();
        client.setFrequency(98500);
        client.jump(Direction.UP);
        client.setPowerMode(PowerMode.NORMAL);
        client.toggleSpeaker();
        assertTrue(controller.commands.isEmpty());

        // Complete binding
        client.onConnected(controller);
        assertTrue(client.isConnected());
        assertSame(controller.state, client.getState());

        // StateListener must immediately receive initial snapshot with SET_INITIAL
        assertEquals(1, receivedMasks.size());
        assertTrue((receivedMasks.get(0) & RadioState.SET_INITIAL) != 0);
        assertSame(controller.state, receivedStates.get(0));
        assertTrue(controller.stateListeners.contains(stateListener));
        assertTrue(controller.eventListeners.contains(eventListener));

        // Queued commands executed in exact FIFO order
        assertEquals(
                Arrays.asList("enable", "setFrequency:98500", "jump:UP", "setPowerMode:normal", "toggleSpeaker"),
                controller.commands
        );

        // Subsequent commands execute immediately
        client.hwSeek(Direction.DOWN);
        client.startRecord();
        client.stopRecord();
        client.hwSearch();
        client.cancelHwSearch();
        client.disable();
        assertEquals(
                Arrays.asList(
                        "enable",
                        "setFrequency:98500",
                        "jump:UP",
                        "setPowerMode:normal",
                        "toggleSpeaker",
                        "hwSeek:DOWN",
                        "startRecord",
                        "stopRecord",
                        "hwSearch",
                        "cancelHwSearch",
                        "disable"
                ),
                controller.commands
        );
    }

    @Test
    public void disconnectBeforeConnectionClearsPendingCommandsAndUnbinds() {
        final RadioClient client = new RadioClient(binder, 90000, null, null);
        client.connect();
        client.enable();
        client.setFrequency(104200);

        client.disconnect();
        assertEquals(1, binder.unbindCount);
        assertFalse(client.isConnected());

        // A stale late onConnected callback must be ignored and must not drain cleared queue
        client.onConnected(controller);
        assertFalse(client.isConnected());
        assertEquals(Collections.emptyList(), controller.commands);

        // Reconnecting later also must not replay previously cleared commands
        client.connect();
        client.onConnected(controller);
        assertTrue(client.isConnected());
        assertEquals(Collections.emptyList(), controller.commands);
    }

    @Test
    public void disconnectUnregistersListenersAndUnbindsService() {
        final StateListener stateListener = (state, mask) -> {};
        final EventListener eventListener = new EventListener() {};

        final RadioClient client = new RadioClient(binder, 87500, stateListener, eventListener);
        client.connect();
        client.onConnected(controller);
        assertTrue(controller.stateListeners.contains(stateListener));
        assertTrue(controller.eventListeners.contains(eventListener));

        client.disconnect();
        assertFalse(client.isConnected());
        assertFalse(controller.stateListeners.contains(stateListener));
        assertFalse(controller.eventListeners.contains(eventListener));
        assertEquals(1, binder.unbindCount);

        // Calling disconnect twice is safe and idempotent
        client.disconnect();
        assertEquals(1, binder.unbindCount);
    }

    private static final class FakeServiceBinder implements RadioClient.ServiceBinder {
        int bindCount = 0;
        int unbindCount = 0;

        @Override
        public boolean bind(@NonNull final ServiceConnection connection) {
            bindCount++;
            return true;
        }

        @Override
        public void unbind(@NonNull final ServiceConnection connection) {
            unbindCount++;
        }
    }

    private static final class FakeController implements RadioServiceController {
        final RadioState state = new RadioState();
        final List<String> commands = new ArrayList<>();
        final Set<StateListener> stateListeners = new CopyOnWriteArraySet<>();
        final Set<EventListener> eventListeners = new CopyOnWriteArraySet<>();

        @NonNull
        @Override
        public RadioState getState() {
            return state;
        }

        @Override
        public void enable() {
            commands.add("enable");
        }

        @Override
        public void disable() {
            commands.add("disable");
        }

        @Override
        public void setFrequency(final int kHz) {
            commands.add("setFrequency:" + kHz);
        }

        @Override
        public void jump(@NonNull final Direction direction) {
            commands.add("jump:" + direction.name());
        }

        @Override
        public void hwSeek(@NonNull final Direction direction) {
            commands.add("hwSeek:" + direction.name());
        }

        @Override
        public void hwSearch() {
            commands.add("hwSearch");
        }

        @Override
        public void cancelHwSearch() {
            commands.add("cancelHwSearch");
        }

        @Override
        public void setPowerMode(@NonNull final PowerMode mode) {
            commands.add("setPowerMode:" + mode.getValue());
        }

        @Override
        public void toggleSpeaker() {
            commands.add("toggleSpeaker");
        }

        @Override
        public void startRecord() {
            commands.add("startRecord");
        }

        @Override
        public void stopRecord() {
            commands.add("stopRecord");
        }

        @Override
        public void addStateListener(@NonNull final StateListener listener) {
            stateListeners.add(listener);
            listener.onStateUpdated(state, RadioState.INITIAL_SNAPSHOT_MASK);
        }

        @Override
        public void removeStateListener(@NonNull final StateListener listener) {
            stateListeners.remove(listener);
        }

        @Override
        public void addEventListener(@NonNull final EventListener listener) {
            eventListeners.add(listener);
        }

        @Override
        public void removeEventListener(@NonNull final EventListener listener) {
            eventListeners.remove(listener);
        }
    }
}
