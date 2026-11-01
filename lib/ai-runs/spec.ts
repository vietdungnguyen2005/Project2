import { z } from "zod";
import { ToolIntent,uuid } from "../ai-gateway/spec";

const reference={run_id:uuid,expected_revision:z.number().int().min(0).max(64)};
export const RunInput=z.discriminatedUnion("action",[
  z.strictObject({action:z.literal("start"),question:z.string().trim().min(1).max(2000),collection_ids:z.array(uuid).max(10).optional()}),
  z.strictObject({action:z.literal("inspect"),run_id:uuid}),
  z.strictObject({action:z.literal("resume"),...reference}),
  z.strictObject({action:z.literal("cancel"),...reference}),
]);

const nullableCode=z.string().regex(/^[A-Z_]{1,40}$/).nullable();
export const RunState=z.strictObject({run_id:uuid,conversation_id:uuid,initial_request_id:uuid,
  status:z.enum(["started","waiting_for_operator","completed","failed","cancelled","expired"]),
  phase:z.enum(["idle","planning","ready","executing","terminal"]),revision:z.number().int().min(0).max(64),step:z.number().int().min(0).max(3),
  created_at:z.number().int().nonnegative(),updated_at:z.number().int().nonnegative(),expires_at:z.number().int().nonnegative(),reason:nullableCode,
  observations:z.array(z.strictObject({call_id:uuid,tool_name:z.enum(["get_integration_profiles","get_imports","inspect_import_job","get_reconciliation_report","search_supplier_knowledge"]),
    status:z.enum(["ok","denied","unavailable","invalid","timeout","budget_exceeded"]),observed_at:z.iso.datetime({offset:true}),truncated:z.boolean(),error_code:nullableCode,item_count:z.number().int().min(0).max(100)})).max(3),
  outcome:z.strictObject({kind:z.enum(["final","handoff"]),status:z.enum(["answered","abstained","human_action_required"]),handoff_reason:nullableCode}).nullable(),can_resume:z.boolean(),can_cancel:z.boolean(),
}).refine(s=>s.can_cancel===["started","waiting_for_operator"].includes(s.status)&&s.can_resume===((s.status==="started"&&["idle","ready"].includes(s.phase))||(s.status==="waiting_for_operator"&&s.reason==="OUTCOME_UNKNOWN")));
export const RunSnapshot=z.strictObject({state:RunState,collection_ids:z.array(uuid).max(10),pending_intent:ToolIntent.nullable(),lease_id:uuid.nullable(),known_job_ids:z.array(uuid).max(100)});
export type Snapshot=z.infer<typeof RunSnapshot>;
