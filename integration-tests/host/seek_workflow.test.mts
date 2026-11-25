import assert from "node:assert/strict";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { FmbinClient, runSeekThenSearch } from "../lib/fmbin_client.mts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const stubPath = path.join(__dirname, "stub_fmbin.mjs");

describe("seekhw -> searchhw synchronization (host stub)", () => {
    let activeClient: FmbinClient | null = null;
    const savedEnv = {
        STUB_EMIT_STATE_ON_SEEK: process.env.STUB_EMIT_STATE_ON_SEEK,
        STUB_SEARCH_FAILED: process.env.STUB_SEARCH_FAILED,
    };

    afterEach(async () => {
        for (const [key, value] of Object.entries(savedEnv)) {
            if (value === undefined) {
                delete process.env[key];
            } else {
                process.env[key] = value;
            }
        }
        if (activeClient) {
            const client = activeClient;
            activeClient = null;
            await client.cleanup();
        }
    });

    it("waits for delayed seek completion before sending searchhw even with stale queued state", async () => {
        process.env.STUB_EMIT_STATE_ON_SEEK = "1";
        const client = FmbinClient.start({ localBinaryPath: stubPath });
        activeClient = client;

        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);

        const searchDone = await runSeekThenSearch(client, {
            seekTimeoutMs: 5000,
            searchTimeoutMs: 5000,
        });
        assert.deepEqual(searchDone.stations, [87500, 101700]);

        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("completes seek -> searchhw when seek lands on the same frequency without a state patch", async () => {
        process.env.STUB_EMIT_STATE_ON_SEEK = "0";
        const client = FmbinClient.start({ localBinaryPath: stubPath });
        activeClient = client;

        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);

        const searchDone = await runSeekThenSearch(client, {
            seekTimeoutMs: 5000,
            searchTimeoutMs: 5000,
        });
        assert.deepEqual(searchDone.stations, [87500, 101700]);

        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("reports search_failed even if the event arrives before the command response", async () => {
        process.env.STUB_SEARCH_FAILED = "1";
        const client = FmbinClient.start({ localBinaryPath: stubPath });
        activeClient = client;
        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal((await client.sendCommand("enable freq=87500")).ok, true);
        await assert.rejects(runSeekThenSearch(client, { searchTimeoutMs: 1000 }), /'searchhw' emitted search_failed/);
    });
});
