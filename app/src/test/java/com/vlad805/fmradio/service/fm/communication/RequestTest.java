package com.vlad805.fmradio.service.fm.communication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class RequestTest {
    @Test
    public void dispatchesResponse() {
        final AtomicReference<String> response = new AtomicReference<>();
        final Request request = new Request("init").onResponse(response::set);

        request.fire("ok");

        assertEquals("ok", response.get());
    }

    @Test
    public void dispatchesError() {
        final AtomicReference<Throwable> receivedError = new AtomicReference<>();
        final Throwable error = new Exception("timeout");
        final Request request = new Request("init").onError(receivedError::set);

        request.fireError(error);

        assertSame(error, receivedError.get());
    }
}
