#!/usr/bin/env node
import readline from "node:readline";

const seekDelayMs = Number(process.env.STUB_SEEK_DELAY_MS ?? "120");
const emitStateOnSeek = process.env.STUB_EMIT_STATE_ON_SEEK !== "0";

let seekInFlight = false;
let seekDoneEmitted = false;
const events = [];

function emit(obj) {
    process.stdout.write(`${JSON.stringify(obj)}\n`);
}

emit({ type: "ready", protocol: 1, pid: process.pid });

const rl = readline.createInterface({ input: process.stdin });
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
        emit({ type: "response", id, ok: true, data: "ok" });
        emit({ type: "search_done", stations: [87500, 101700] });
        return;
    }

    if (cmd === "shutdown") {
        emit({ type: "response", id, ok: true, data: "ok" });
        process.exit(0);
    }

    emit({ type: "response", id, ok: false, data: "ERR_UNK_CMD" });
});
