import { execFile } from "node:child_process";
import { access } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export interface AdbOptions {
    serial?: string;
}

function buildAdbArgs(args: string[], options: AdbOptions = {}): string[] {
    const serial = options.serial ?? process.env.RFM_ADB_SERIAL;
    if (serial && serial.trim().length > 0) {
        return ["-s", serial.trim(), ...args];
    }
    return args;
}

export async function runAdb(args: string[], options: AdbOptions = {}): Promise<string> {
    const { stdout } = await execFileAsync("adb", buildAdbArgs(args, options), {
        encoding: "utf8",
    });
    return stdout.trim();
}

export async function ensureDeviceAndRoot(options: AdbOptions = {}): Promise<void> {
    const state = await runAdb(["get-state"], options);
    if (state !== "device") {
        throw new Error(`Expected adb device state 'device', got '${state}'`);
    }

    const idOutput = await runAdb(["shell", "su", "-c", "id -u"], options);
    if (idOutput !== "0") {
        throw new Error(`Root shell check failed: expected uid 0, got '${idOutput}'`);
    }
}

export async function resolveAssetNameForDevice(options: AdbOptions = {}): Promise<string> {
    const abi = await runAdb(["shell", "getprop", "ro.product.cpu.abi"], options);
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
    const assetName = await resolveAssetNameForDevice(options);
    const localPath = path.join(repoRoot, "app", "src", "main", "assets", assetName);
    await access(localPath);

    await runAdb(["push", localPath, remotePath], options);
    await runAdb(["shell", "chmod", "755", remotePath], options);
    return assetName;
}
