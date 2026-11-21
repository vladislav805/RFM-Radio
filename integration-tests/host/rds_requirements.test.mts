import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
    allRequiredRdsFound,
    getMissingRequiredRdsFields,
    getSkippedOptionalRdsFields,
    parseRdsRequirements,
    resolveRdsRequirementsFromEnv,
    type RdsChecklist,
} from "../lib/rds_requirements.mts";

describe("RDS requirement configurability", () => {
    it("defaults to ps and rt with opt-in rt_plus and af", () => {
        const defaults = parseRdsRequirements(undefined);
        assert.deepEqual([...defaults], ["ps", "rt"]);

        const all = parseRdsRequirements("all");
        assert.deepEqual([...all], ["ps", "rt", "rt_plus", "af"]);

        const custom = parseRdsRequirements("ps, rt, rt_plus, af");
        assert.deepEqual([...custom], ["ps", "rt", "rt_plus", "af"]);

        assert.throws(() => parseRdsRequirements("ps,bogus"), /Unknown RFM_RDS_REQUIRE/);
    });

    it("does not fail when optional rt_plus and af are absent under default policy", () => {
        const required = parseRdsRequirements(undefined);
        const checklist: RdsChecklist = {
            ps: { found: true, freqKhz: 87500, value: "STATION" },
            rt: { found: true, freqKhz: 87500, value: "Now playing" },
            rtPlus: { found: false },
            af: { found: false },
        };

        assert.equal(allRequiredRdsFound(checklist, required), true);
        assert.deepEqual(getMissingRequiredRdsFields(checklist, required), []);
        assert.deepEqual(getSkippedOptionalRdsFields(checklist, required), ["rt_plus", "af"]);
    });

    it("reports missing opt-in fields only when explicitly required", () => {
        const strict = parseRdsRequirements("ps,rt,rt_plus,af");
        const checklist: RdsChecklist = {
            ps: { found: true, freqKhz: 87500, value: "STATION" },
            rt: { found: true, freqKhz: 87500, value: "Now playing" },
            rtPlus: { found: false },
            af: { found: true, freqKhz: 87500, value: [95400] },
        };

        assert.equal(allRequiredRdsFound(checklist, strict), false);
        assert.deepEqual(getMissingRequiredRdsFields(checklist, strict), ["rt_plus"]);
        assert.deepEqual(getSkippedOptionalRdsFields(checklist, strict), []);
    });

    it("wires RFM_RDS_REQUIRE from an environment object without leaking into pure parser defaults", () => {
        const previous = process.env.RFM_RDS_REQUIRE;
        process.env.RFM_RDS_REQUIRE = "all";
        try {
            assert.deepEqual([...parseRdsRequirements(undefined)], ["ps", "rt"]);
            assert.deepEqual(
                [...resolveRdsRequirementsFromEnv()],
                ["ps", "rt", "rt_plus", "af"],
            );
        } finally {
            if (previous === undefined) {
                delete process.env.RFM_RDS_REQUIRE;
            } else {
                process.env.RFM_RDS_REQUIRE = previous;
            }
        }

        assert.deepEqual([...resolveRdsRequirementsFromEnv({})], ["ps", "rt"]);
        assert.deepEqual(
            [...resolveRdsRequirementsFromEnv({ RFM_RDS_REQUIRE: "all" })],
            ["ps", "rt", "rt_plus", "af"],
        );
        assert.deepEqual(
            [...resolveRdsRequirementsFromEnv({ RFM_RDS_REQUIRE: "ps,af" })],
            ["ps", "af"],
        );
    });
});
