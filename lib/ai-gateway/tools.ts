import { z } from "zod";
import { JavaSession, readBounded } from "./io";
import { projectTool } from "./projection";
import { uuid, type Intent } from "./spec";

export type Observation = {
  call_id: string; tool_name: Intent["tool_name"]; status: "ok" | "denied" | "unavailable" | "invalid" | "timeout";
  observed_at: string; truncated: boolean; data: unknown; error_code: string | null;
};

export async function executeRead(intent: Intent, session: JavaSession): Promise<Observation> {
  const base = { call_id: intent.call_id, tool_name: intent.tool_name, observed_at: new Date().toISOString(),
    truncated: false, data: null };
  const fail = (status: Observation["status"], error_code: string): Observation => ({ ...base, observed_at:new Date().toISOString(), status, error_code });
  try {
    let path: string;
    switch (intent.tool_name) {
      case "get_integration_profiles": path = "/api/ops/integration-profiles"; break;
      case "get_imports": path = "/api/ops/imports"; break;
      case "inspect_import_job": path = `/api/ops/imports/${intent.arguments.job_id}/inspection`; break;
      case "get_reconciliation_report": path = "/api/ops/reconciliations"; break;
      default: return fail("invalid", "TOOL_OUTPUT_INVALID");
    }
    const response = await session.get(path);
    if (!response.ok) void response.body?.cancel().catch(() => undefined);
    if (response.status === 401 || response.status === 403) return fail("denied", "ACCESS_DENIED");
    if ([400, 404, 409].includes(response.status)) return fail("unavailable", "UPSTREAM_RESOURCE_UNAVAILABLE");
    if (!response.ok) return fail("unavailable", "UPSTREAM_UNAVAILABLE");
    let raw: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(await readBounded(response, 5_000_000, session.signal)));
    if (intent.tool_name === "get_reconciliation_report") {
      const reports = z.array(z.object({ migrationJobId: uuid, createdAt: z.iso.datetime({ offset: true }) }).passthrough()).max(20).parse(raw);
      raw = reports.filter(r => r.migrationJobId === intent.arguments.job_id)
        .sort((a,b) => Date.parse(b.createdAt)-Date.parse(a.createdAt))[0];
      if (!raw) return fail("unavailable", "UPSTREAM_RESOURCE_UNAVAILABLE");
    }
    const projected = projectTool(intent.tool_name, raw);
    if (!projected.success) return fail("invalid", "TOOL_OUTPUT_INVALID");
    const result: Observation = { ...base, observed_at: new Date().toISOString(), status: "ok", error_code: null,
      ...projected.data };
    if (Buffer.byteLength(JSON.stringify(result), "utf8") > 16_384) return fail("invalid", "TOOL_RESULT_TOO_LARGE");
    return result;
  } catch (error) {
    if(session.signal.aborted) return fail("timeout","DEADLINE_EXCEEDED");
    if(error instanceof Error && error.message === "BODY_LIMIT") return fail("invalid","TOOL_RESULT_TOO_LARGE");
    if(error instanceof SyntaxError || error instanceof z.ZodError) return fail("invalid","TOOL_OUTPUT_INVALID");
    return fail("unavailable", "UPSTREAM_UNAVAILABLE");
  }
}
