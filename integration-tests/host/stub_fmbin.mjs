#!/usr/bin/env node
import readline from "node:readline";

const seekDelayMs = Number(process.env.STUB_SEEK_DELAY_MS ?? "120");
const emitStateOnSeek = process.env.STUB_EMIT_STATE_ON_SEEK !== "0";
const shutdownDelayMs = Number(process.env.STUB_SHUTDOWN_DELAY_MS ?? "0");
const shutdownMode = process.env.STUB_SHUTDOWN_MODE ?? "ok";
const dropAfterEnable = process.env.STUB_DROP_AFTER_ENABLE === "1";

let seekInFlight = false;
let seekDoneEmitted = false;
const events = [];

function emit(obj) {
    process.stdout.write(`${JSON.stringify(obj)}\n`);
}

emit({ type: "ready", pid: process.pid });

const rl = readline.createInterface({ input: process.stdin });
rl.on("close", () => {
    setTimeout(() => {
        process.exit(0);
    }, shutdownDelayMs);
});

rl.on("line", (line) => {
    const trimmed = line.trim();
    if (!trimmed) {
        return;
    }

    const tab = trimmed.indexOf("\t");
    if (tab < 0) {
        return;
    }

    const id = Number(trimmed.slice(0, tab));
    const cmd = trimmed.slice(tab + 1);
    events.push(cmd);

    if (cmd === "init") {
        emit({ type: "response", id, ok: true, data: "ok" });
        return;
    }

    if (cmd.startsWith("enable")) {
        // Emit an initial state patch before/around enable response to test queue draining.
        emit({ type: "state", frequency: 87500 });
        emit({ type: "response", id, ok: true, data: "ok" });
        if (dropAfterEnable) {
            setTimeout(() => process.exit(1), 20);
        }
        return;
    }

    if (cmd.startsWith("seekhw")) {
        seekInFlight = true;
        emit({ type: "response", id, ok: true, data: "ok" });
        setTimeout(() => {
            seekInFlight = false;
            seekDoneEmitted = true;
            if (emitStateOnSeek) {
                emit({ type: "state", frequency: 89100 });
            }
            emit({ type: "seek_done" });
        }, seekDelayMs);
        return;
    }

    if (cmd === "searchhw") {
        if (seekInFlight || !seekDoneEmitted) {
            emit({ type: "response", id, ok: false, data: "ERR_FAILED" });
            return;
        }
        if (process.env.STUB_SEARCH_FAILED === "1") {
            emit({ type: "search_failed" });
            emit({ type: "response", id, ok: true, data: "ok" });
            return;
        }
        emit({ type: "response", id, ok: true, data: "ok" });
        emit({ type: "search_done", stations: [87500, 101700] });
        return;
    }

    if (cmd === "shutdown") {
        if (shutdownMode === "fail") {
            emit({ type: "response", id, ok: false, data: "ERR_FAILED" });
            return;
        }
        if (shutdownMode === "hang") {
            // Never respond; EOF or kill must tear down the process.
            return;
        }
        emit({ type: "response", id, ok: true, data: "ok" });
        setTimeout(() => {
            process.exit(0);
        }, shutdownDelayMs);
        return;
    }

    emit({ type: "response", id, ok: false, data: "ERR_UNK_CMD" });
});
