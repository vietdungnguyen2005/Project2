import { z } from "zod";

export const uuid = z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
export const BrowserInput = z.strictObject({
  question: z.string().trim().min(1).max(2000),
  collection_ids: z.array(uuid).max(10).optional(),
});
const empty = z.strictObject({});
const job = z.strictObject({ job_id: uuid });
export const ToolIntent = z.discriminatedUnion("tool_name", [
  z.strictObject({ call_id: uuid, tool_name: z.literal("get_integration_profiles"), arguments: empty }),
  z.strictObject({ call_id: uuid, tool_name: z.literal("get_imports"), arguments: empty }),
  z.strictObject({ call_id: uuid, tool_name: z.literal("inspect_import_job"), arguments: job }),
  z.strictObject({ call_id: uuid, tool_name: z.literal("get_reconciliation_report"), arguments: job }),
  z.strictObject({ call_id: uuid, tool_name: z.literal("search_supplier_knowledge"), arguments: z.strictObject({
    query: z.string().min(1).max(2000), collection_ids: z.array(uuid).min(1).max(10),
    top_k: z.number().int().min(1).max(5),
  }) }),
]);
export type Intent = z.infer<typeof ToolIntent>;
export const FailureCode = z.enum(["AI_DISABLED", "INPUT_INVALID", "ACCESS_DENIED", "SESSION_EXPIRED",
  "CONFIGURATION_UNAVAILABLE", "OUTPUT_INVALID", "DEPENDENCY_UNAVAILABLE", "DEADLINE_EXCEEDED",
  "BUDGET_EXCEEDED", "STEP_CONFLICT"]);
export type GatewayFailure = z.infer<typeof FailureCode>;
export type Result<T> = { success: true; data: T } | { success: false; code: GatewayFailure; status: number };
export interface AgentGatewaySpec { execute(request: Request): Promise<Result<unknown>> }

const identity = { protocol_version: z.literal(1), request_id: uuid, run_id: uuid, step: z.number().int().min(0).max(3) };
const message = z.string().min(1).max(8000).refine(s => !/[<>]|https?:\/\//i.test(s));
export const StepResponse = z.union([
  z.strictObject({ ...identity, kind: z.literal("tool_intent"), intent: ToolIntent }),
  z.strictObject({ ...identity, kind: z.literal("final"), message, citations: z.array(z.never()).max(0),
    status: z.enum(["answered", "abstained"]), handoff_reason: z.null() }),
  z.strictObject({ ...identity, kind: z.literal("handoff"), message, citations: z.array(z.never()).max(0),
    status: z.literal("human_action_required"), handoff_reason: z.enum(["PROFILE_CHANGE", "IMPORT_CONFIRM", "JOB_RESTART", "RECONCILIATION_CREATE", "OTHER_BUSINESS_MUTATION"]) }),
]);
