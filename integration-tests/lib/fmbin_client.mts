import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import readline from "node:readline";
import type { AdbOptions } from "./adb.mts";

export interface ReadyEvent {
    type: "ready";
    protocol: number;
    pid: number;
    backend?: "v4l2_qcom" | "hal_helium" | "none";
}

export interface ResponseMessage {
    type: "response";
    id: number;
    ok: boolean;
    data: string;
}

export interface RtPlusInfo {
    title?: string;
    artist?: string;
    album?: string;
}

export interface RdsState {
    ps?: string;
    rt?: string;
    pi?: string;
    country?: string;
    pty?: number;
    af?: number[];
    rt_plus?: RtPlusInfo;
}

export interface StateEvent {
    type: "state";
    frequency?: number;
    stereo?: boolean;
    rmssi?: number;
    sinr?: number;
    rds?: RdsState;
}

export interface SearchDoneEvent {
    type: "search_done";
    stations: number[];
}

export interface DisabledEvent {
    type: "disabled";
}

export type FmbinMessage =
    | ReadyEvent
    | ResponseMessage
    | StateEvent
    | SearchDoneEvent
    | DisabledEvent;

interface PendingWaiter {
    predicate: (msg: FmbinMessage) => boolean;
    resolve: (msg: FmbinMessage) => void;
    reject: (err: Error) => void;
    timer: NodeJS.Timeout;
}

export interface FmbinClientOptions extends AdbOptions {
    remotePath?: string;
    localBinaryPath?: string;
}

export class FmbinClient {
    private readonly proc: ChildProcessWithoutNullStreams;
    private readonly rl: readline.Interface;
    private readonly messageQueue: FmbinMessage[] = [];
    private readonly waiters: PendingWaiter[] = [];
    private readonly stderrLines: string[] = [];
    private nextRequestId = 1;
    private exitPromise: Promise<{ code: number | null; signal: NodeJS.Signals | null }>;
    private exited = false;
    private exitError: Error | null = null;

    private constructor(proc: ChildProcessWithoutNullStreams) {
        this.proc = proc;
        this.rl = readline.createInterface({ input: proc.stdout });

        this.rl.on("line", (line) => {
            const trimmed = line.trim();
            if (trimmed.length === 0) {
                return;
            }
            let parsed: FmbinMessage;
            try {
                parsed = JSON.parse(trimmed) as FmbinMessage;
            } catch (err) {
                this.failAllWaiters(
                    new Error(`Invalid JSON from fmbin: '${trimmed}' (${String(err)})`),
                );
                return;
            }
            this.dispatchMessage(parsed);
        });

        const stderrRl = readline.createInterface({ input: proc.stderr });
        stderrRl.on("line", (line) => {
            this.stderrLines.push(line);
            if (this.stderrLines.length > 200) {
                this.stderrLines.shift();
            }
        });

        this.exitPromise = new Promise((resolve, reject) => {
            proc.on("error", (err) => {
                this.exited = true;
                this.exitError = err;
                this.failAllWaiters(err);
                reject(err);
            });
            proc.on("close", (code, signal) => {
                this.exited = true;
                const exitErr = new Error(
                    `fmbin exited unexpectedly (code=${String(code)}, signal=${String(signal)})\n` +
                        this.formatDiagnostics(),
                );
                this.failAllWaiters(exitErr);
                resolve({ code, signal });
            });
        });
    }

    static start(options: FmbinClientOptions = {}): FmbinClient {
        let cmd: string;
        let args: string[];

        if (options.localBinaryPath) {
            cmd = options.localBinaryPath;
            args = [];
        } else {
            const remotePath = options.remotePath ?? "/data/local/tmp/fmbin";
            const serial = options.serial ?? process.env.RFM_ADB_SERIAL;
            cmd = "adb";
            args = [
                ...(serial && serial.trim().length > 0 ? ["-s", serial.trim()] : []),
                "shell",
                "su",
                "-c",
                remotePath,
            ];
        }

        const proc = spawn(cmd, args, {
            stdio: ["pipe", "pipe", "pipe"],
        });
        return new FmbinClient(proc);
    }

    getDiagnostics(): string {
        return this.formatDiagnostics();
    }

    async waitForReady(timeoutMs = 5000): Promise<ReadyEvent> {
        const msg = await this.waitForMessage(
            (m): m is ReadyEvent => m.type === "ready",
            timeoutMs,
            "ready handshake",
        );
        return msg;
    }

    async sendCommand(command: string, timeoutMs = 10000): Promise<ResponseMessage> {
        const id = this.nextRequestId++;
        const frame = `${id}\t${command}\n`;
        this.proc.stdin.write(frame, "utf8");

        const msg = await this.waitForMessage(
            (m): m is ResponseMessage => m.type === "response" && m.id === id,
            timeoutMs,
            `response for '${command}' (id=${id})`,
        );
        return msg;
    }

    async waitForEvent<T extends FmbinMessage>(
        predicate: (msg: FmbinMessage) => msg is T,
        timeoutMs = 10000,
        description = "event",
    ): Promise<T> {
        return this.waitForMessage(predicate, timeoutMs, description);
    }

    drainMessages(): FmbinMessage[] {
        return this.messageQueue.splice(0, this.messageQueue.length);
    }

    async shutdown(timeoutMs = 5000): Promise<{ code: number | null; signal: NodeJS.Signals | null }> {
        if (!this.exited) {
            const resp = await this.sendCommand("shutdown", timeoutMs);
            if (!resp.ok) {
                throw new Error(`shutdown command failed: ${resp.data}\n${this.formatDiagnostics()}`);
            }
        }
        return this.waitForExit(timeoutMs);
    }

    closeStdin(): void {
        this.proc.stdin.end();
    }

    async waitForExit(
        timeoutMs = 5000,
    ): Promise<{ code: number | null; signal: NodeJS.Signals | null }> {
        let timer: NodeJS.Timeout | undefined;
        try {
            return await Promise.race([
                this.exitPromise,
                new Promise<never>((_, reject) => {
                    timer = setTimeout(() => {
                        reject(
                            new Error(
                                `Timed out after ${timeoutMs} ms waiting for fmbin exit\n` +
                                    this.formatDiagnostics(),
                            ),
                        );
                    }, timeoutMs);
                }),
            ]);
        } finally {
            if (timer) {
                clearTimeout(timer);
            }
        }
    }

    forceKill(): void {
        this.rl.close();
        if (!this.exited) {
            this.proc.kill("SIGKILL");
        }
    }

    private dispatchMessage(msg: FmbinMessage): void {
        const waiterIndex = this.waiters.findIndex((w) => w.predicate(msg));
        if (waiterIndex >= 0) {
            const [waiter] = this.waiters.splice(waiterIndex, 1);
            clearTimeout(waiter.timer);
            waiter.resolve(msg);
            return;
        }
        this.messageQueue.push(msg);
    }

    private waitForMessage<T extends FmbinMessage>(
        predicate: (msg: FmbinMessage) => msg is T,
        timeoutMs: number,
        description: string,
    ): Promise<T> {
        const queuedIndex = this.messageQueue.findIndex(predicate);
        if (queuedIndex >= 0) {
            const [msg] = this.messageQueue.splice(queuedIndex, 1);
            return Promise.resolve(msg as T);
        }

        if (this.exited) {
            return Promise.reject(
                this.exitError ??
                    new Error(
                        `Cannot wait for ${description}: fmbin already exited\n` +
                            this.formatDiagnostics(),
                    ),
            );
        }

        return new Promise<T>((resolve, reject) => {
            const timer = setTimeout(() => {
                const idx = this.waiters.indexOf(waiter);
                if (idx >= 0) {
                    this.waiters.splice(idx, 1);
                }
                reject(
                    new Error(
                        `Timed out after ${timeoutMs} ms waiting for ${description}\n` +
                            this.formatDiagnostics(),
                    ),
                );
            }, timeoutMs);

            const waiter: PendingWaiter = {
                predicate,
                resolve: (msg) => resolve(msg as T),
                reject,
                timer,
            };
            this.waiters.push(waiter);
        });
    }

    private failAllWaiters(err: Error): void {
        while (this.waiters.length > 0) {
            const waiter = this.waiters.shift();
            if (waiter) {
                clearTimeout(waiter.timer);
                waiter.reject(err);
            }
        }
    }

    private formatDiagnostics(): string {
        if (this.stderrLines.length === 0) {
            return "stderr: <empty>";
        }
        return `stderr (last ${this.stderrLines.length} lines):\n${this.stderrLines.join("\n")}`;
    }
}
