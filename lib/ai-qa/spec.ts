import { z } from "zod";
import { uuid } from "../ai-gateway/spec";

const profile={profile_id:z.string().regex(/^[a-z][a-z0-9-]{0,47}$/),profile_version:z.number().int().min(1).max(2147483647)};
export const AskInput=z.strictObject({...profile,question:z.string().trim().min(1).max(2000),language:z.enum(["en","ja"])});
export const SourceInput=z.strictObject({...profile,chunk_id:uuid});
export const Citation=z.strictObject({document_id:uuid,version_id:uuid,chunk_id:uuid,
  locator:z.discriminatedUnion("kind",[z.strictObject({kind:z.literal("section"),value:z.string().min(1).max(160)}),z.strictObject({kind:z.literal("page"),value:z.number().int().min(1).max(200)})])});
const WireCitation=Citation.extend({source_url:z.string()}).refine(c=>c.source_url==="/ai/citations/"+c.chunk_id).transform(({source_url:_url,...c})=>c);
const answerFields={request_id:uuid,status:z.enum(["answered","abstained"]),answer:z.string().min(1).max(8000).nullable(),
  reason:z.enum(["NO_SUPPORTED_EVIDENCE","CONFLICTING_EVIDENCE","OUTDATED_ONLY","AMBIGUOUS_REQUEST"]).nullable(),
  confidence:z.enum(["low","unknown"]),provider_mode:z.enum(["fake","live","local"])};
const supported=(a:{status:string;answer:string|null;reason:string|null;confidence:string;citations:unknown[]})=>a.status==="answered"?Boolean(a.answer&&a.citations.length&&a.reason===null&&a.confidence==="low"):a.answer===null&&a.reason!==null&&a.confidence==="unknown";
export const WireAnswer=z.strictObject({...answerFields,citations:z.array(WireCitation).max(10)}).refine(supported);
export const Answer=z.strictObject({...answerFields,citations:z.array(Citation).max(10)}).refine(supported);
const sourceFields={request_id:uuid,excerpt:z.string().max(2000),truncated:z.boolean()};
export const WireSource=z.strictObject({...sourceFields,citation:WireCitation});
export const Source=z.strictObject({...sourceFields,citation:Citation});
export type PolicyAnswer=z.infer<typeof Answer>;
export type PolicySource=z.infer<typeof Source>;
