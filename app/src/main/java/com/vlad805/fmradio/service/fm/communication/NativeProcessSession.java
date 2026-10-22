package com.vlad805.fmradio.service.fm.communication;

import android.util.Log;

import com.vlad805.fmradio.Utils;
import com.vlad805.fmradio.service.fm.FMEventCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class NativeProcessSession {
    private static final String TAG = "RFM-QCOM";
    private static final int PROTOCOL_VERSION = 1;
    private static final long PROCESS_EXIT_POLL_MS = 50L;

    public interface LifecycleListener {
        void onUnexpectedExit(int exitCode);
    }

    private static final class PendingRequest {
        final long id;
        final Request request;
        ScheduledFuture<?> timeout;

        PendingRequest(final long id, final Request request) {
            this.id = id;
            this.request = request;
        }
    }

    private final String binaryPath;
    private final NativeEventParser eventParser;
    private final LifecycleListener lifecycleListener;
    private final Object stateLock = new Object();
    private final Object writerLock = new Object();
    private final Queue<Request> requestQueue = new ArrayDeque<>();
    private final Map<Long, PendingRequest> pendingRequests = new HashMap<>();
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "NativeRequestTimeout");
        thread.setDaemon(true);
        return thread;
    });

    private Process process;
    private BufferedWriter writer;
    private PendingRequest activeRequest;
    private Throwable launchFailure;
    private long nextRequestId = 1L;
    private int nativePid = -1;
    private boolean ready;
    private boolean stopping;
    private boolean exited;

    public NativeProcessSession(
            final String binaryPath,
            final FMEventCallback eventCallback,
            final LifecycleListener lifecycleListener
    ) {
        this.binaryPath = binaryPath;
        this.eventParser = new NativeEventParser(eventCallback);
        this.lifecycleListener = lifecycleListener;
    }

    public void start(final long timeoutMs) throws IOException, InterruptedException {
        final Process startedProcess = new ProcessBuilder("su").start();
        synchronized (stateLock) {
            process = startedProcess;
            writer = new BufferedWriter(new OutputStreamWriter(
                    startedProcess.getOutputStream(),
                    StandardCharsets.UTF_8
            ));
        }

        startStdoutReader(startedProcess);
        startStderrReader(startedProcess);
        startExitWaiter(startedProcess);

        try {
            writeLine("exec " + shellQuote(binaryPath));
        } catch (final IOException e) {
            abortStart();
            throw e;
        }

        try {
            if (!readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                abortStart();
                throw new IOException("Timed out waiting for native process handshake");
            }
        } catch (final InterruptedException e) {
            abortStart();
            throw e;
        }

        final Throwable failure;
        synchronized (stateLock) {
            failure = ready && !exited
                    ? null
                    : (launchFailure != null ? launchFailure : new IOException("Native process exited during handshake"));
        }
        if (failure != null) {
            abortStart();
            final IOException error = new IOException("Native process exited before handshake");
            error.initCause(failure);
            throw error;
        }
    }

    public boolean isRunning() {
        synchronized (stateLock) {
            return ready && !stopping && !exited;
        }
    }

    public int getNativePid() {
        synchronized (stateLock) {
            return nativePid;
        }
    }

    public void send(final Request request) {
        final Throwable error;
        synchronized (stateLock) {
            if (!ready || stopping || exited) {
                error = new IOException("Native process is not running");
            } else {
                requestQueue.offer(request);
                error = null;
            }
        }

        if (error != null) {
            request.fireError(error);
            return;
        }
        dispatchNextRequest();
    }

    public boolean stop(final long timeoutMs) {
        final List<Request> cancelled = new ArrayList<>();
        final CountDownLatch shutdownResponse = new CountDownLatch(1);
        final PendingRequest shutdownRequest;
        final boolean waitForExistingStop;

        synchronized (stateLock) {
            if (process == null || exited) {
                stopping = true;
                return true;
            }
            if (stopping) {
                waitForExistingStop = true;
                shutdownRequest = null;
            } else {
                waitForExistingStop = false;
                stopping = true;
                while (!requestQueue.isEmpty()) {
                    cancelled.add(requestQueue.remove());
                }

                final Request request = new Request("shutdown", (int) timeoutMs)
                        .onResponse(response -> shutdownResponse.countDown())
                        .onError(error -> shutdownResponse.countDown());
                shutdownRequest = new PendingRequest(nextRequestId++, request);
                pendingRequests.put(shutdownRequest.id, shutdownRequest);
            }
        }

        if (waitForExistingStop) {
            return waitForExit(timeoutMs + 3500L);
        }

        final IOException stoppedError = new IOException("Native process is stopping");
        for (final Request request : cancelled) {
            try {
                request.fireError(stoppedError);
            } catch (final RuntimeException e) {
                Log.e(TAG, "Native cancellation callback failed", e);
            }
        }

        try {
            writeControlRequest(shutdownRequest.id, shutdownRequest.request.getCommand());
            shutdownResponse.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final IOException e) {
            Log.w(TAG, "Failed to send native shutdown command", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeInput();
        }

        if (waitForExit(1500L)) {
            return true;
        }

        final int pid = getNativePid();
        if (pid > 0) {
            Utils.runAsRoot("kill -TERM " + pid + " 2>/dev/null");
        }
        destroyLauncherProcess();

        if (waitForExit(2000L)) {
            return true;
        }

        if (pid > 0) {
            Utils.runAsRoot("kill -KILL " + pid + " 2>/dev/null");
        }
        destroyLauncherProcess();
        return waitForExit(500L);
    }

    private void dispatchNextRequest() {
        final PendingRequest pending;
        synchronized (stateLock) {
            if (activeRequest != null || stopping || exited) {
                return;
            }

            final Request request = requestQueue.poll();
            if (request == null) {
                return;
            }

            pending = new PendingRequest(nextRequestId++, request);
            activeRequest = pending;
            pendingRequests.put(pending.id, pending);
            pending.timeout = timeoutExecutor.schedule(
                    () -> onRequestTimeout(pending.id),
                    request.getTimeout(),
                    TimeUnit.MILLISECONDS
            );
        }

        try {
            writeControlRequest(pending.id, pending.request.getCommand());
        } catch (final IOException e) {
            completeWithError(pending.id, e);
        }
    }

    private void handleStdoutLine(final String line) {
        final JSONObject json;
        try {
            json = new JSONObject(line);
        } catch (final JSONException e) {
            Log.w(TAG, "Ignoring non-protocol native stdout: " + line);
            return;
        }

        final String type = json.optString("type", "");
        switch (type) {
            case "ready":
                handleReady(json);
                break;
            case "response":
                handleResponse(json);
                break;
            default:
                eventParser.handle(json);
                break;
        }
    }

    private void handleReady(final JSONObject json) {
        final int protocol = json.optInt("protocol", -1);
        final int pid = json.optInt("pid", -1);
        synchronized (stateLock) {
            if (protocol != PROTOCOL_VERSION || pid <= 0) {
                launchFailure = new IOException("Unsupported native protocol handshake");
            } else {
                nativePid = pid;
                ready = true;
            }
        }
        readyLatch.countDown();
    }

    private void handleResponse(final JSONObject json) {
        final long id = json.optLong("id", -1L);
        final PendingRequest pending;
        synchronized (stateLock) {
            pending = pendingRequests.remove(id);
            if (pending == null) {
                Log.w(TAG, "Unexpected native response id=" + id);
                return;
            }
            if (pending.timeout != null) {
                pending.timeout.cancel(false);
            }
            if (activeRequest == pending) {
                activeRequest = null;
            }
        }

        try {
            pending.request.fire(json.optString("data", ""));
        } catch (final RuntimeException e) {
            Log.e(TAG, "Native response callback failed", e);
        } finally {
            dispatchNextRequest();
        }
    }

    private void onRequestTimeout(final long id) {
        completeWithError(id, new IOException("Native request timed out"));
    }

    private void completeWithError(final long id, final Throwable error) {
        final PendingRequest pending;
        synchronized (stateLock) {
            pending = pendingRequests.remove(id);
            if (pending == null) {
                return;
            }
            if (pending.timeout != null) {
                pending.timeout.cancel(false);
            }
            if (activeRequest == pending) {
                activeRequest = null;
            }
        }

        try {
            pending.request.fireError(error);
        } catch (final RuntimeException e) {
            Log.e(TAG, "Native error callback failed", e);
        } finally {
            dispatchNextRequest();
        }
    }

    private void startStdoutReader(final Process source) {
        startDaemonThread("NativeStdout", () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    source.getInputStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        handleStdoutLine(line);
                    } catch (final RuntimeException e) {
                        Log.e(TAG, "Failed to handle native protocol message", e);
                    }
                }
            } catch (final IOException e) {
                synchronized (stateLock) {
                    if (!stopping && !exited) {
                        Log.w(TAG, "Native stdout closed", e);
                    }
                }
            }
        });
    }

    private void startStderrReader(final Process source) {
        startDaemonThread("NativeStderr", () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    source.getErrorStream(),
                    StandardCharsets.UTF_8
            ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, line);
                }
            } catch (final IOException e) {
                synchronized (stateLock) {
                    if (!stopping && !exited) {
                        Log.w(TAG, "Native stderr closed", e);
                    }
                }
            }
        });
    }

    private void startExitWaiter(final Process source) {
        startDaemonThread("NativeProcessWaiter", () -> {
            int exitCode = -1;
            try {
                exitCode = source.waitFor();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            handleProcessExit(exitCode);
        });
    }

    private void handleProcessExit(final int exitCode) {
        final List<Request> failed = new ArrayList<>();
        final boolean unexpected;
        final boolean completedHandshake;
        synchronized (stateLock) {
            if (exited) {
                return;
            }

            exited = true;
            completedHandshake = ready;
            unexpected = completedHandshake && !stopping;
            if (!completedHandshake) {
                launchFailure = new IOException("Root process exited with code " + exitCode);
            }

            for (final PendingRequest pending : pendingRequests.values()) {
                if (pending.timeout != null) {
                    pending.timeout.cancel(false);
                }
                failed.add(pending.request);
            }
            pendingRequests.clear();
            activeRequest = null;
            while (!requestQueue.isEmpty()) {
                failed.add(requestQueue.remove());
            }
        }

        readyLatch.countDown();
        timeoutExecutor.shutdownNow();
        closeInput();
        final IOException error = new IOException("Native process exited with code " + exitCode);
        for (final Request request : failed) {
            try {
                request.fireError(error);
            } catch (final RuntimeException e) {
                Log.e(TAG, "Native exit callback failed", e);
            }
        }

        if (unexpected) {
            lifecycleListener.onUnexpectedExit(exitCode);
        }
    }

    private void abortStart() {
        synchronized (stateLock) {
            stopping = true;
        }
        closeInput();
        destroyLauncherProcess();
        waitForExit(500L);
    }

    private void writeControlRequest(final long id, final String command) throws IOException {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.indexOf('\t') >= 0) {
            throw new IOException("Invalid native command framing");
        }
        writeLine(id + "\t" + command);
    }

    private void writeLine(final String line) throws IOException {
        synchronized (writerLock) {
            if (writer == null) {
                throw new IOException("Native stdin is closed");
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
        }
    }

    private void closeInput() {
        synchronized (writerLock) {
            if (writer == null) {
                return;
            }
            try {
                writer.close();
            } catch (final IOException e) {
                Log.d(TAG, "Native stdin already closed", e);
            }
            writer = null;
        }
    }

    private void destroyLauncherProcess() {
        final Process runningProcess;
        synchronized (stateLock) {
            runningProcess = process;
        }
        if (runningProcess != null && processIsAlive(runningProcess)) {
            runningProcess.destroy();
        }
    }

    private boolean waitForExit(final long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final Process runningProcess;
            synchronized (stateLock) {
                if (exited) {
                    return true;
                }
                runningProcess = process;
            }
            if (runningProcess == null || !processIsAlive(runningProcess)) {
                return true;
            }
            try {
                Thread.sleep(PROCESS_EXIT_POLL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        synchronized (stateLock) {
            return exited || process == null || !processIsAlive(process);
        }
    }

    private static boolean processIsAlive(final Process process) {
        try {
            process.exitValue();
            return false;
        } catch (final IllegalThreadStateException ignored) {
            return true;
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void startDaemonThread(final String name, final Runnable runnable) {
        final Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }
}
