import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import {
    DEFAULT_ADB_PUSH_TIMEOUT_MS,
    DEFAULT_ADB_TIMEOUT_MS,
    ensureDeviceAndRoot,
    pushFmbin,
    resolveAdbTimeoutMs,
    runAdb,
} from "../lib/adb.mts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const stubAdbPath = path.join(__dirname, "stub_adb.mjs");

function isPidAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch {
        return false;
    }
}

describe("adb preparation timeouts (host stub)", () => {
    const savedEnv = {
        STUB_ADB_HANG_CMD: process.env.STUB_ADB_HANG_CMD,
        STUB_ADB_PID_FILE: process.env.STUB_ADB_PID_FILE,
    };

    const tempDirs: string[] = [];

    afterEach(async () => {
        for (const [key, val] of Object.entries(savedEnv)) {
            if (val === undefined) {
                delete process.env[key];
            } else {
                process.env[key] = val;
            }
        }
        while (tempDirs.length > 0) {
            const dir = tempDirs.pop();
            if (dir) {
                await rm(dir, { recursive: true, force: true });
            }
        }
    });

    it("uses larger default timeout for push than short commands", () => {
        const shortTimeout = resolveAdbTimeoutMs(["get-state"]);
        const pushTimeout = resolveAdbTimeoutMs(["push", "a", "b"]);

        assert.equal(shortTimeout, DEFAULT_ADB_TIMEOUT_MS);
        assert.equal(pushTimeout, DEFAULT_ADB_PUSH_TIMEOUT_MS);
        assert.ok(pushTimeout > shortTimeout);
        assert.equal(resolveAdbTimeoutMs(["push", "a", "b"], { timeoutMs: 750 }), 750);
    });

    it("kills timed-out adb child and names args and timeout", async () => {
        const tmpDir = await mkdtemp(path.join(os.tmpdir(), "rfm-adb-timeout-"));
        tempDirs.push(tmpDir);
        const pidFile = path.join(tmpDir, "adb.pid");

        process.env.STUB_ADB_HANG_CMD = "id -u";
        process.env.STUB_ADB_PID_FILE = pidFile;

        const startedAt = Date.now();
        await assert.rejects(
            () => runAdb(["shell", "su", "-c", "id -u"], { adbPath: stubAdbPath, timeoutMs: 120 }),
            (err: Error) => {
                assert.match(err.message, /shell su -c id -u/);
                assert.match(err.message, /120 ms/);
                return true;
            },
        );
        const elapsedMs = Date.now() - startedAt;
        assert.ok(elapsedMs < 2000, `Expected timeout quickly, took ${elapsedMs} ms`);

        const spawnedPid = Number((await readFile(pidFile, "utf8")).trim());
        assert.ok(spawnedPid > 0);
        assert.equal(isPidAlive(spawnedPid), false);
    });

    it("enforces overall deadline in ensureDeviceAndRoot when root hangs", async () => {
        const tmpDir = await mkdtemp(path.join(os.tmpdir(), "rfm-adb-root-"));
        tempDirs.push(tmpDir);
        const pidFile = path.join(tmpDir, "adb.pid");

        process.env.STUB_ADB_HANG_CMD = "id -u";
        process.env.STUB_ADB_PID_FILE = pidFile;

        const startedAt = Date.now();
        await assert.rejects(
            () => ensureDeviceAndRoot({ adbPath: stubAdbPath, deadlineMs: 150 }),
            (err: Error) => {
                assert.match(err.message, /id -u/);
                assert.match(err.message, /timed out|deadline/);
                return true;
            },
        );
        const elapsedMs = Date.now() - startedAt;
        assert.ok(elapsedMs < 2000, `Expected ensureDeviceAndRoot to fail within deadline, took ${elapsedMs} ms`);

        const spawnedPid = Number((await readFile(pidFile, "utf8")).trim());
        assert.equal(isPidAlive(spawnedPid), false);
    });

    it("enforces deadline in pushFmbin when adb push hangs", async () => {
        const tmpDir = await mkdtemp(path.join(os.tmpdir(), "rfm-adb-push-"));
        tempDirs.push(tmpDir);
        const assetsDir = path.join(tmpDir, "app", "src", "main", "assets");
        await mkdir(assetsDir, { recursive: true });
        await writeFile(path.join(assetsDir, "fmbin-aarch64"), "stub", "utf8");
        const pidFile = path.join(tmpDir, "adb.pid");

        process.env.STUB_ADB_HANG_CMD = "push";
        process.env.STUB_ADB_PID_FILE = pidFile;

        await assert.rejects(
            () =>
                pushFmbin(tmpDir, "/data/local/tmp/fmbin", {
                    adbPath: stubAdbPath,
                    deadlineMs: 150,
                }),
            (err: Error) => {
                assert.match(err.message, /push/);
                assert.match(err.message, /timed out|deadline/);
                return true;
            },
        );

        const spawnedPid = Number((await readFile(pidFile, "utf8")).trim());
        assert.equal(isPidAlive(spawnedPid), false);
    });
});
