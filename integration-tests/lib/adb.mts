import { execFile } from "node:child_process";
import { access } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export const DEFAULT_ADB_TIMEOUT_MS = 10_000;
export const DEFAULT_ADB_PUSH_TIMEOUT_MS = 30_000;
export const DEFAULT_PREP_DEADLINE_MS = 45_000;

export interface AdbOptions {
    serial?: string;
    adbPath?: string;
    timeoutMs?: number;
    deadlineMs?: number;
}

function buildAdbArgs(args: string[], options: AdbOptions = {}): string[] {
    const serial = options.serial ?? process.env.RFM_ADB_SERIAL;
    if (serial && serial.trim().length > 0) {
        return ["-s", serial.trim(), ...args];
    }
    return args;
}

function isPushCommand(args: string[]): boolean {
    return args[0] === "push";
}

export function resolveAdbTimeoutMs(
    args: string[],
    options: AdbOptions = {},
    deadlineAtMs?: number,
): number {
    const baseTimeoutMs =
        options.timeoutMs ?? (isPushCommand(args) ? DEFAULT_ADB_PUSH_TIMEOUT_MS : DEFAULT_ADB_TIMEOUT_MS);
    if (deadlineAtMs === undefined) {
        return baseTimeoutMs;
    }

    const remainingMs = deadlineAtMs - Date.now();
    if (remainingMs <= 0) {
        const adbArgs = buildAdbArgs(args, options);
        throw new Error(
            `adb ${adbArgs.join(" ")} aborted: preparation deadline (${options.deadlineMs ?? DEFAULT_PREP_DEADLINE_MS} ms) exceeded`,
        );
    }

    return Math.min(baseTimeoutMs, remainingMs);
}

async function runAdbWithDeadline(
    args: string[],
    options: AdbOptions,
    deadlineAtMs?: number,
): Promise<string> {
    const timeoutMs = resolveAdbTimeoutMs(args, options, deadlineAtMs);
    return runAdb(args, { ...options, timeoutMs });
}

export async function runAdb(args: string[], options: AdbOptions = {}): Promise<string> {
    const adbPath = options.adbPath ?? process.env.RFM_ADB_BIN ?? "adb";
    const adbArgs = buildAdbArgs(args, options);
    const timeoutMs = resolveAdbTimeoutMs(args, options);

    try {
        const { stdout } = await execFileAsync(adbPath, adbArgs, {
            encoding: "utf8",
            timeout: timeoutMs,
            killSignal: "SIGKILL",
        });
        return stdout.trim();
    } catch (err) {
        const execErr = err as NodeJS.ErrnoException & {
            killed?: boolean;
            signal?: NodeJS.Signals | null;
            stderr?: string;
        };
        if (execErr.killed) {
            throw new Error(
                `adb ${adbArgs.join(" ")} timed out after ${timeoutMs} ms (killed with ${execErr.signal ?? "SIGKILL"})`,
            );
        }
        throw err;
    }
}

export async function ensureDeviceAndRoot(options: AdbOptions = {}): Promise<void> {
    const deadlineAtMs = Date.now() + (options.deadlineMs ?? DEFAULT_PREP_DEADLINE_MS);
    const state = await runAdbWithDeadline(["get-state"], options, deadlineAtMs);
    if (state !== "device") {
        throw new Error(`Expected adb device state 'device', got '${state}'`);
    }

    const idOutput = await runAdbWithDeadline(["shell", "su", "-c", "id -u"], options, deadlineAtMs);
    if (idOutput !== "0") {
        throw new Error(`Root shell check failed: expected uid 0, got '${idOutput}'`);
    }
}

export async function resolveAssetNameForDevice(
    options: AdbOptions = {},
    deadlineAtMs?: number,
): Promise<string> {
    const abi = await runAdbWithDeadline(["shell", "getprop", "ro.product.cpu.abi"], options, deadlineAtMs);
    if (abi === "arm64-v8a") {
        return "fmbin-aarch64";
    }
    if (abi === "armeabi-v7a" || abi === "armeabi") {
        return "fmbin-armv7a";
    }
    throw new Error(`Unsupported device ABI '${abi}'`);
}

export async function pushFmbin(
    repoRoot: string,
    remotePath: string,
    options: AdbOptions = {},
): Promise<string> {
    const deadlineAtMs = Date.now() + (options.deadlineMs ?? DEFAULT_PREP_DEADLINE_MS);
    const assetName = await resolveAssetNameForDevice(options, deadlineAtMs);
    const localPath = path.join(repoRoot, "app", "src", "main", "assets", assetName);
    await access(localPath);

    await runAdbWithDeadline(["push", localPath, remotePath], options, deadlineAtMs);
    await runAdbWithDeadline(["shell", "chmod", "755", remotePath], options, deadlineAtMs);
    return assetName;
}
