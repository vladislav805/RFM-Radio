import assert from "node:assert/strict";
import path from "node:path";
import { afterEach, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { ensureDeviceAndRoot, pushFmbin } from "./lib/adb.mts";
import {
    FmbinClient,
    runSeekThenSearch,
    type DisabledEvent,
} from "./lib/fmbin_client.mts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");
const remoteBinaryPath = process.env.RFM_REMOTE_FMBIN ?? "/data/local/tmp/fmbin";
const region = process.env.RFM_REGION ?? "eu";
const initialFreqKhz = Number(process.env.RFM_INITIAL_FREQ ?? "87500");
const secondaryFreqKhz = Number(process.env.RFM_SECONDARY_FREQ ?? "100000");

function buildEnableCommand(freqKhz = initialFreqKhz): string {
    return (
        `enable freq=${freqKhz} region=${region} spacing=100 ` +
        `stereo=1 soft_mute=1 antenna=0 af=1`
    );
}

describe("fmbin device integration", () => {
    let activeClient: FmbinClient | null = null;

    before(async () => {
        await ensureDeviceAndRoot();
        if (process.env.RFM_SKIP_PUSH !== "1") {
            await pushFmbin(repoRoot, remoteBinaryPath);
        }
    });

    afterEach(async () => {
        if (activeClient) {
            const client = activeClient;
            activeClient = null;
            await client.cleanup();
        }
    });

    it("completes ready handshake, init, and clean shutdown", async () => {
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        const ready = await client.waitForReady();
        assert.ok(ready.pid > 0);
        assert.ok(
            ready.backend && ["v4l2_qcom", "v4l2_exynos", "hal_helium"].includes(ready.backend),
            `unexpected backend: ${String(ready.backend)}`,
        );

        const initResp = await client.sendCommand("init");
        assert.equal(initResp.ok, true, initResp.data);
        assert.ok(initResp.data.startsWith("ok"), initResp.data);

        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("rejects out-of-band enable config with ERR_IVD_CFG", async () => {
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        await client.waitForReady();
        const initResp = await client.sendCommand("init");
        assert.equal(initResp.ok, true, initResp.data);

        const badEnable = await client.sendCommand(buildEnableCommand(10000));
        assert.equal(badEnable.ok, false);
        assert.match(badEnable.data, /ERR_IVD_CFG/);

        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("runs enable, setfreq, jump, disable, and emits disabled event", async () => {
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        await client.waitForReady();

        const initResp = await client.sendCommand("init");
        assert.equal(initResp.ok, true, initResp.data);

        const enableResp = await client.sendCommand(buildEnableCommand(initialFreqKhz), 15000);
        assert.equal(enableResp.ok, true, `${enableResp.data}\n${client.getDiagnostics()}`);

        const tuneResp = await client.sendCommand(`setfreq ${secondaryFreqKhz}`, 10000);
        assert.equal(tuneResp.ok, true, tuneResp.data);

        const jumpUpResp = await client.sendCommand("jump 1", 10000);
        assert.equal(jumpUpResp.ok, true, jumpUpResp.data);

        const jumpDownResp = await client.sendCommand("jump -1", 10000);
        assert.equal(jumpDownResp.ok, true, jumpDownResp.data);

        for (const mode of ["1", "2", "0"]) {
            const notchResp = await client.sendCommand(`set_notch_filter ${mode}`, 10000);
            assert.equal(notchResp.ok, true, notchResp.data);
        }
        const badNotchResp = await client.sendCommand("set_notch_filter 3", 10000);
        assert.equal(badNotchResp.ok, false);
        assert.match(badNotchResp.data, /ERR_IVD_CFG/);

        const disableResp = await client.sendCommand("disable", 10000);
        assert.equal(disableResp.ok, true, disableResp.data);

        await client.waitForEvent(
            (m): m is DisabledEvent => m.type === "disabled",
            5000,
            "disabled event",
        );

        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("supports seekhw and searchhw with search_done", async () => {
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal(
            (await client.sendCommand(buildEnableCommand(initialFreqKhz), 15000)).ok,
            true,
        );

        const searchDone = await runSeekThenSearch(client, {
            seekTimeoutMs: Number(process.env.RFM_SEEK_TIMEOUT_MS ?? "30000"),
            searchTimeoutMs: Number(process.env.RFM_SEARCH_TIMEOUT_MS ?? "90000"),
        });
        assert.ok(Array.isArray(searchDone.stations));
        assert.ok(searchDone.stations.length <= 64);

        for (let i = 1; i < searchDone.stations.length; i++) {
            assert.ok(
                searchDone.stations[i] > searchDone.stations[i - 1],
                `Expected strictly sorted stations, got ${JSON.stringify(searchDone.stations)}`,
            );
        }

        assert.equal((await client.sendCommand("disable", 10000)).ok, true);
        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });

    it("fails closed and exits cleanly on stdin EOF while enabled", async () => {
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);
        assert.equal(
            (await client.sendCommand(buildEnableCommand(initialFreqKhz), 15000)).ok,
            true,
        );

        client.closeStdin();
        const exit = await client.waitForExit(10000);
        assert.equal(exit.code, 0);
        activeClient = null;
    });
});
