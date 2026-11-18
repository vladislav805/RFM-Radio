import assert from "node:assert/strict";
import path from "node:path";
import { afterEach, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";
import { ensureDeviceAndRoot, pushFmbin } from "./lib/adb.mts";
import {
    FmbinClient,
    type RtPlusInfo,
    type StateEvent,
} from "./lib/fmbin_client.mts";
import {
    allRequiredRdsFound,
    getMissingRequiredRdsFields,
    getSkippedOptionalRdsFields,
    parseRdsRequirements,
    type RdsChecklist,
} from "./lib/rds_requirements.mts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, "..");
const remoteBinaryPath = process.env.RFM_REMOTE_FMBIN ?? "/data/local/tmp/fmbin";
const region = process.env.RFM_REGION ?? "eu";
const initialFreqKhz = Number(process.env.RFM_INITIAL_FREQ ?? "87500");
const rdsDwellMs = Number(process.env.RFM_RDS_DWELL_MS ?? "6000");
const maxSeeks = Number(process.env.RFM_RDS_MAX_SEEKS ?? "35");

function hasNonEmptyRtPlus(rtPlus: RtPlusInfo): boolean {
    return (
        (rtPlus.title?.trim().length ?? 0) > 0 ||
        (rtPlus.artist?.trim().length ?? 0) > 0 ||
        (rtPlus.album?.trim().length ?? 0) > 0
    );
}

function updateChecklist(
    checklist: RdsChecklist,
    event: StateEvent,
    currentFreqKhz: number,
): void {
    const rds = event.rds;
    if (!rds) {
        return;
    }

    if (!checklist.ps.found && rds.ps !== undefined && rds.ps.trim().length > 0) {
        checklist.ps = { found: true, freqKhz: currentFreqKhz, value: rds.ps };
    }

    if (!checklist.rt.found && rds.rt !== undefined && rds.rt.trim().length > 0) {
        checklist.rt = { found: true, freqKhz: currentFreqKhz, value: rds.rt };
    }

    if (!checklist.rtPlus.found && rds.rt_plus !== undefined && hasNonEmptyRtPlus(rds.rt_plus)) {
        checklist.rtPlus = { found: true, freqKhz: currentFreqKhz, value: rds.rt_plus };
    }

    if (!checklist.af.found && rds.af !== undefined && rds.af.length > 0) {
        checklist.af = { found: true, freqKhz: currentFreqKhz, value: rds.af };
    }
}

describe("fmbin RDS integration", () => {
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

    it("observes required RDS fields across seekhw 1 steps", async (t) => {
        const requiredFields = parseRdsRequirements();
        const client = FmbinClient.start({ remotePath: remoteBinaryPath });
        activeClient = client;

        await client.waitForReady();
        assert.equal((await client.sendCommand("init")).ok, true);

        const enableCmd =
            `enable freq=${initialFreqKhz} region=${region} spacing=100 ` +
            `stereo=1 soft_mute=1 antenna=0 af=1`;
        assert.equal((await client.sendCommand(enableCmd, 15000)).ok, true);

        const checklist: RdsChecklist = {
            ps: { found: false },
            rt: { found: false },
            rtPlus: { found: false },
            af: { found: false },
        };

        let currentFreqKhz = initialFreqKhz;

        for (let step = 0; step <= maxSeeks; step++) {
            const deadline = Date.now() + rdsDwellMs;
            while (Date.now() < deadline && !allRequiredRdsFound(checklist, requiredFields)) {
                const remaining = deadline - Date.now();
                if (remaining <= 0) {
                    break;
                }
                try {
                    const event = await client.waitForEvent(
                        (m): m is StateEvent => m.type === "state",
                        Math.min(remaining, 1000),
                        "state event",
                    );
                    if (event.frequency !== undefined) {
                        currentFreqKhz = event.frequency;
                    }
                    updateChecklist(checklist, event, currentFreqKhz);
                } catch {
                    // Wait for more RDS events until dwell deadline.
                }
            }

            if (allRequiredRdsFound(checklist, requiredFields) || step === maxSeeks) {
                break;
            }

            client.drainMessages();
            const seekResp = await client.sendCommand("seekhw 1", 10000);
            assert.equal(seekResp.ok, true, seekResp.data);

            // Wait for seek tune event so currentFreqKhz and RDS dwell match the next station.
            try {
                const tuned = await client.waitForEvent(
                    (m): m is StateEvent => m.type === "state" && m.frequency !== undefined,
                    5000,
                    "seek tune state",
                );
                if (tuned.frequency !== undefined) {
                    currentFreqKhz = tuned.frequency;
                }
                updateChecklist(checklist, tuned, currentFreqKhz);
            } catch {
                // Continue even if frequency state patch arrived before or was unchanged.
            }
        }

        const optionalSkipped = getSkippedOptionalRdsFields(checklist, requiredFields);
        const missingRequired = getMissingRequiredRdsFields(checklist, requiredFields);
        t.diagnostic(
            `RDS checklist (required=${[...requiredFields].join(",") || "none"}): ${JSON.stringify(checklist)}`,
        );
        if (optionalSkipped.length > 0) {
            t.diagnostic(
                `Optional RDS fields not observed in broadcast RF (not a failure): ${optionalSkipped.join(",")}`,
            );
        }

        assert.deepEqual(
            missingRequired,
            [],
            `Required RDS field(s) not observed (${missingRequired.join(",")}): ${JSON.stringify(checklist)}`,
        );

        assert.equal((await client.sendCommand("disable", 10000)).ok, true);
        const exit = await client.shutdown();
        assert.equal(exit.code, 0);
        activeClient = null;
    });
});
