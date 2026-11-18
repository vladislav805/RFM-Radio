import type { RtPlusInfo } from "./fmbin_client.mts";

export type RdsRequirementKey = "ps" | "rt" | "rt_plus" | "af";

export interface RdsChecklist {
    ps: { found: boolean; freqKhz?: number; value?: string };
    rt: { found: boolean; freqKhz?: number; value?: string };
    rtPlus: { found: boolean; freqKhz?: number; value?: RtPlusInfo };
    af: { found: boolean; freqKhz?: number; value?: number[] };
}

export const DEFAULT_RDS_REQUIREMENTS: readonly RdsRequirementKey[] = ["ps", "rt"];

const ALL_RDS_REQUIREMENTS: readonly RdsRequirementKey[] = ["ps", "rt", "rt_plus", "af"];

export function parseRdsRequirements(raw = process.env.RFM_RDS_REQUIRE): Set<RdsRequirementKey> {
    if (raw === undefined) {
        return new Set(DEFAULT_RDS_REQUIREMENTS);
    }

    const trimmed = raw.trim();
    if (trimmed.length === 0) {
        return new Set();
    }

    const result = new Set<RdsRequirementKey>();
    for (const part of trimmed.split(",")) {
        const token = part.trim().toLowerCase();
        if (token.length === 0) {
            continue;
        }
        if (token === "all") {
            for (const key of ALL_RDS_REQUIREMENTS) {
                result.add(key);
            }
            continue;
        }
        if (token === "ps" || token === "rt" || token === "af") {
            result.add(token);
            continue;
        }
        if (token === "rt_plus" || token === "rtplus") {
            result.add("rt_plus");
            continue;
        }
        throw new Error(
            `Unknown RFM_RDS_REQUIRE token '${part.trim()}'; expected ps, rt, rt_plus, af, or all`,
        );
    }

    return result;
}

export function isRequirementSatisfied(
    checklist: RdsChecklist,
    key: RdsRequirementKey,
): boolean {
    switch (key) {
        case "ps":
            return checklist.ps.found;
        case "rt":
            return checklist.rt.found;
        case "rt_plus":
            return checklist.rtPlus.found;
        case "af":
            return checklist.af.found;
    }
}

export function allRequiredRdsFound(
    checklist: RdsChecklist,
    required: ReadonlySet<RdsRequirementKey>,
): boolean {
    if (required.size === 0) {
        return (
            checklist.ps.found &&
            checklist.rt.found &&
            checklist.rtPlus.found &&
            checklist.af.found
        );
    }

    for (const key of required) {
        if (!isRequirementSatisfied(checklist, key)) {
            return false;
        }
    }
    return true;
}

export function getMissingRequiredRdsFields(
    checklist: RdsChecklist,
    required: ReadonlySet<RdsRequirementKey>,
): RdsRequirementKey[] {
    const missing: RdsRequirementKey[] = [];
    for (const key of required) {
        if (!isRequirementSatisfied(checklist, key)) {
            missing.push(key);
        }
    }
    return missing;
}

export function getSkippedOptionalRdsFields(
    checklist: RdsChecklist,
    required: ReadonlySet<RdsRequirementKey>,
): RdsRequirementKey[] {
    const skipped: RdsRequirementKey[] = [];
    for (const key of ALL_RDS_REQUIREMENTS) {
        if (!required.has(key) && !isRequirementSatisfied(checklist, key)) {
            skipped.push(key);
        }
    }
    return skipped;
}
