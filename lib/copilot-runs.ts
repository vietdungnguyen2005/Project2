import { z } from "zod";
import { RunState } from "./ai-runs/spec";
import { uuid } from "./ai-gateway/spec";

export const LiveObservation=z.strictObject({call_id:uuid,tool_name:z.enum(["get_integration_profiles","get_imports","inspect_import_job","get_reconciliation_report","search_supplier_knowledge"]),status:z.enum(["ok","denied","unavailable","invalid","timeout"]),observed_at:z.iso.datetime({offset:true}),truncated:z.boolean(),error_code:z.string().max(80).nullable(),data:z.unknown()});
export const RunReply=z.strictObject({run:RunState,observations:z.array(LiveObservation).max(3)}).refine(r=>r.observations.every(o=>r.run.observations.some(s=>s.call_id===o.call_id&&s.tool_name===o.tool_name&&s.status===o.status)));
export type Investigation=z.infer<typeof RunReply>;
export type ReadObservation=z.infer<typeof LiveObservation>;
export const toolLabels:Record<ReadObservation["tool_name"],string>={get_integration_profiles:"Read integration profiles",get_imports:"List import jobs",inspect_import_job:"Inspect import job",get_reconciliation_report:"Read reconciliation report",search_supplier_knowledge:"Search supplier knowledge"};
const job=z.object({job_id:uuid,status:z.string().max(32),total_rows:z.number().int().nonnegative(),applied_rows:z.number().int().nonnegative(),rejected_rows:z.number().int().nonnegative()});
const rows=z.object({imports:z.array(job).max(20)});
const inspection=z.object({job,work_state:z.string().max(32),errors:z.array(z.object({row_number:z.number().int(),code:z.string().max(80)})).max(100)});
const profiles=z.object({profiles:z.array(z.object({profile_id:z.string().max(48),version:z.number().int(),vendor_code:z.string().max(32),active:z.boolean()})).max(100)});
const report=z.object({job_id:uuid,mismatch_count:z.number().int(),changed_since_import_rows:z.number().int(),stock_not_verified_rows:z.number().int()});
const knowledge=z.object({hits:z.array(z.object({excerpt:z.string().max(2000),citation:z.object({locator:z.object({kind:z.enum(["section","page"]),value:z.union([z.string().max(160),z.number()])})})})).max(5)});
export function observationLines(observation:ReadObservation):string[]{
  if(observation.status!=="ok")return ["Read unavailable: "+(observation.error_code??observation.status)];
  const describe=(j:z.infer<typeof job>)=>`${j.job_id} · ${j.status} · ${j.applied_rows}/${j.total_rows} applied · ${j.rejected_rows} rejected`;
  if(observation.tool_name==="get_imports"){const result=rows.safeParse(observation.data);if(result.success)return result.data.imports.length?result.data.imports.map(describe):["No import jobs returned by the authorized read."];}
  if(observation.tool_name==="inspect_import_job"){const result=inspection.safeParse(observation.data);if(result.success)return [describe(result.data.job),"Work state: "+result.data.work_state,...result.data.errors.slice(0,10).map(e=>`Row ${e.row_number}: ${e.code}`)];}
  if(observation.tool_name==="get_integration_profiles"){const result=profiles.safeParse(observation.data);if(result.success)return result.data.profiles.length?result.data.profiles.slice(0,10).map(p=>`${p.profile_id} / v${p.version} / ${p.vendor_code} / ${p.active?"active":"inactive"}`):["No profiles returned."];}
  if(observation.tool_name==="get_reconciliation_report"){const result=report.safeParse(observation.data);if(result.success)return [`Job: ${result.data.job_id}`,`Mismatches: ${result.data.mismatch_count}`,`Changed since import: ${result.data.changed_since_import_rows}`,`Stock not verified: ${result.data.stock_not_verified_rows}`];}
  if(observation.tool_name==="search_supplier_knowledge"){const result=knowledge.safeParse(observation.data);if(result.success)return result.data.hits.length?result.data.hits.map(h=>`${h.citation.locator.kind} ${h.citation.locator.value}: ${h.excerpt}`):["No current knowledge hits returned."];}
  return ["The read completed, but its details cannot be displayed safely."];
}
