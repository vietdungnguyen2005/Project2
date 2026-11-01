import { z } from "zod";
import { uuid, type Intent, type Result } from "./spec";

const header = z.string().min(1).max(80).refine(s => s === s.trim() && ![...s].some(c => c.charCodeAt(0) < 32 || (c.charCodeAt(0) >= 127 && c.charCodeAt(0) <= 159)));
const mapping = z.strictObject({ sku: header, name: header, on_hand: header }).refine(m => new Set(Object.values(m)).size === 3);
const profileId = z.string().regex(/^[a-z][a-z0-9-]{0,47}$/);
const integer = z.number().int().min(0).max(2147483647);
const version = z.number().int().min(1).max(2147483647);
const count = z.number().int().min(0).max(1000);
function rowCode(reason: string): string {
  const prefix = reason.match(/^(UNKNOWN_SKU|VENDOR_MISMATCH|STALE_STOCK_VERSION|STALE_PRODUCT_VERSION|DUPLICATE_SKU):/);
  if (prefix) return prefix[1];
  if (reason === "invalid SKU") return "INVALID_SKU";
  if (reason === "invalid product name") return "INVALID_NAME";
  if (reason === "on_hand must be a non-negative integer" || reason === "on_hand must be an integer between 0 and 1000000") return "INVALID_QUANTITY";
  return "UNKNOWN_ROW_ERROR";
}
const validation = z.strictObject({
  sku: z.strictObject({ type: z.literal("string"), minLength: z.literal(1), maxLength: z.literal(64) }),
  name: z.strictObject({ type: z.literal("string"), minLength: z.literal(1), maxLength: z.literal(160) }),
  on_hand: z.strictObject({ type: z.literal("integer"), min: z.literal(0), max: z.literal(1000000) }),
});
const Profile = z.object({ profileId, version, encoding: z.enum(["UTF-8", "CP932"]), vendorCode: z.string().min(1).max(32),
  mapping, skuNormalization: z.enum(["TRIM", "TRIM_UPPER"]), active: z.boolean(),
  requiredFields: z.tuple([z.literal("sku"), z.literal("name"), z.literal("on_hand")]), validation,
  normalization: z.strictObject({ sku: z.enum(["TRIM", "TRIM_UPPER"]), name: z.literal("TRIM"), on_hand: z.literal("TRIM_INTEGER") }),
}).refine(p => p.normalization.sku === p.skuNormalization).transform(p => ({
  profile_id: p.profileId, version: p.version, vendor_code: p.vendorCode, encoding: p.encoding,
  mapping: p.mapping, sku_normalization: p.skuNormalization, active: p.active, required_fields: p.requiredFields,
}));
const Job = z.object({ id: uuid, status: z.enum(["STAGED", "RUNNING", "COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED"]),
  totalRows: count, validRows: count, appliedRows: count, rejectedRows: count,
  checkpointRow: z.number().int().min(0).max(1001), importMode: z.enum(["VERSIONED_STOCK", "METADATA_ONLY", "LEGACY_UNVERIFIED"]),
  profileId: profileId.nullable(), profileVersion: version.nullable(),
}).refine(j => (j.profileId === null) === (j.profileVersion === null)).transform(j => ({
  job_id: j.id, status: j.status, total_rows: j.totalRows, valid_rows: j.validRows, applied_rows: j.appliedRows,
  rejected_rows: j.rejectedRows, checkpoint_row: j.checkpointRow, import_mode: j.importMode,
  profile_id: j.profileId, profile_version: j.profileVersion,
}));

export function projectTool(tool: Intent["tool_name"], raw: unknown): Result<{ data: unknown; truncated: boolean }> {
  try {
    if (tool === "get_integration_profiles") {
      const data = z.array(Profile).max(100).parse(raw);
      return { success: true, data: { data: { profiles: data }, truncated: false } };
    }
    if (tool === "get_imports") {
      const data = z.array(Job).max(20).parse(raw);
      return { success: true, data: { data: { imports: data }, truncated: false } };
    }
    if (tool === "inspect_import_job") {
      const item = z.object({ job: Job, workState: z.enum(["READY", "CLAIMED", "FINISHED", "FAILED", "BLOCKED"]),
        recoveryCount: integer, restartAllowed: z.boolean(), errors: z.array(z.object({
          rowNumber: z.number().int().min(1).max(1001), reason: z.string().max(8000),
        })).max(1000) }).parse(raw);
      return { success: true, data: { data: { job: item.job, work_state: item.workState,
        recovery_count: item.recoveryCount, restart_allowed: item.restartAllowed,
        errors: item.errors.slice(0, 100).map(e => ({ row_number: e.rowNumber, code: rowCode(e.reason) })),
      }, truncated: item.errors.length > 100 } };
    }
    if (tool === "get_reconciliation_report") {
      const item = z.object({ id: uuid, migrationJobId: uuid, status: z.literal("COMPLETED"),
        mismatchCount: integer.max(1000000), changedSinceImportRows: integer.max(1000000), stockNotVerifiedRows: integer.max(1000000),
        discrepancies: z.array(z.object({ sku: z.string().min(1).max(64),
          type: z.enum(["MISSING_PRODUCT", "STOCK_VERSION_REGRESSION", "QUANTITY_MISMATCH", "PRODUCT_VERSION_REGRESSION", "PRICE_MISMATCH"]),
        })).max(10000) }).parse(raw);
      return { success: true, data: { data: { report_id: item.id, job_id: item.migrationJobId,
        status: item.status, mismatch_count: item.mismatchCount,
        changed_since_import_rows: item.changedSinceImportRows, stock_not_verified_rows: item.stockNotVerifiedRows,
        discrepancies: item.discrepancies.slice(0, 100),
      }, truncated: item.discrepancies.length > 100 } };
    }
    return { success: false, code: "OUTPUT_INVALID", status: 502 };
  } catch { return { success: false, code: "OUTPUT_INVALID", status: 502 }; }
}
