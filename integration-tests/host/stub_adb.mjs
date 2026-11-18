#!/usr/bin/env node
import { writeFileSync } from "node:fs";

const args = process.argv.slice(2);
const pidFile = process.env.STUB_ADB_PID_FILE;
if (pidFile) {
    writeFileSync(pidFile, `${process.pid}\n`, "utf8");
}

const hangCmd = process.env.STUB_ADB_HANG_CMD ?? "";
const cmd = args.join(" ");

if (hangCmd === "*" || (hangCmd.length > 0 && cmd.includes(hangCmd))) {
    // Ignore SIGTERM so we verify the caller escalates or uses SIGKILL.
    process.on("SIGTERM", () => {});
    setInterval(() => {}, 1000);
} else if (cmd.includes("get-state")) {
    process.stdout.write("device\n");
} else if (cmd.includes("id -u")) {
    process.stdout.write("0\n");
} else if (cmd.includes("ro.product.cpu.abi")) {
    process.stdout.write("arm64-v8a\n");
} else if (cmd.includes("push") || cmd.includes("chmod")) {
    process.stdout.write("ok\n");
} else {
    process.stderr.write(`Unexpected stub adb command: ${cmd}\n`);
    process.exit(1);
}
