import assert from "node:assert/strict";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { FmbinClient } from "../lib/fmbin_client.mts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const stubPath = path.join(__dirname, "stub_fmbin.mjs");

describe("failure cleanup awaits remote receiver teardown (host stub)", () => {
    const savedEnv = {
        STUB_SHUTDOWN_DELAY_MS: process.env.STUB_SHUTDOWN_DELAY_MS,
        STUB_SHUTDOWN_MODE: process.env.STUB_SHUTDOWN_MODE,
        STUB_DROP_AFTER_ENABLE: process.env.STUB_DROP_AFTER_ENABLE,
    };

    let activeClient: FmbinClient | null = null;

    afterEach(async () => {
        for (const [key, val] of Object.entries(savedEnv)) {
            if (val === undefined) {
                delete process.env[key];
            } else {
                process.env[key] = val;
            }
        }
        if (activeClient) {
            const client = activeClient;
            activeClient = null;
            await client.cleanup();
        }
    });

    it("waits for delayed remote teardown before resolving cleanup after failure", async () => {
        process.env.STUB_SHUTDOWN_DELAY_MS = "150";
        process.env.STUB_SHUTDOWN_MODE = "ok";

        let remoteTeardownCompleted = false;
        let killedPid: number | null = null;

        const client = FmbinClient.start({
            localBinaryPath: stubPath,
            terminateRemotePid: async (pid) => {
                killedPid = pid;
                await new Promise((resolve) => setTimeout(resolve, 120));
                remoteTeardownCompleted = true;
            },
        });
        activeClient = client;

        const ready = await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);

        const startedAt = Date.now();
        await client.cleanup(5000);
        const elapsedMs = Date.now() - startedAt;
        activeClient = null;

        assert.equal(killedPid, ready.pid);
        assert.equal(remoteTeardownCompleted, true);
        assert.ok(elapsedMs >= 240, `Expected cleanup to wait for child + remote teardown, got ${elapsedMs}ms`);
    });

    it("falls back to stdin EOF when shutdown command fails", async () => {
        process.env.STUB_SHUTDOWN_DELAY_MS = "100";
        process.env.STUB_SHUTDOWN_MODE = "fail";

        let killedPid: number | null = null;
        const client = FmbinClient.start({
            localBinaryPath: stubPath,
            terminateRemotePid: async (pid) => {
                killedPid = pid;
            },
        });
        activeClient = client;

        const ready = await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);

        const startedAt = Date.now();
        await client.cleanup(5000);
        const elapsedMs = Date.now() - startedAt;
        activeClient = null;

        assert.equal(killedPid, ready.pid);
        assert.ok(elapsedMs >= 90, `Expected EOF teardown delay before completion, got ${elapsedMs}ms`);
    });

    it("terminates targeted remote PID on transport loss after enable", async () => {
        process.env.STUB_DROP_AFTER_ENABLE = "1";

        const killedPids: number[] = [];
        let remoteAlive = true;

        const client = FmbinClient.start({
            localBinaryPath: stubPath,
            terminateRemotePid: async (pid) => {
                killedPids.push(pid);
                await new Promise((resolve) => setTimeout(resolve, 80));
                remoteAlive = false;
            },
        });
        activeClient = client;

        const ready = await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);

        // Wait for simulated transport loss (local process dies right after enable).
        await client.waitForExit(2000);
        assert.equal(remoteAlive, true);

        await client.cleanup(5000);
        activeClient = null;

        assert.deepEqual(killedPids, [ready.pid]);
        assert.equal(remoteAlive, false);
    });
});
