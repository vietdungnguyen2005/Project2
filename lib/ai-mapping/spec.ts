import { z } from "zod";
import { uuid } from "../ai-gateway/spec";

export const MappingInput=z.strictObject({raw_base64:z.string().min(4).max(6_666_668),
  declared_encoding:z.enum(["UTF-8","CP932"]),source_profile_id:z.string().regex(/^[a-z][a-z0-9-]{0,47}$/),
  source_profile_version:z.number().int().min(1).max(2147483647),method:z.enum(["rule","ai"]),synthetic:z.literal(true)});

export const Header=z.string().min(1).max(80).refine(s=>s===s.trim()&&![...s].some(c=>c.charCodeAt(0)<32||(c.charCodeAt(0)>=127&&c.charCodeAt(0)<=159)));
export const Columns=z.strictObject({sku:Header,name:Header,on_hand:Header}).refine(m=>new Set(Object.values(m)).size===3);
const Target=z.enum(["sku","name","on_hand"]);
const Citation=z.strictObject({document_id:uuid,version_id:uuid,chunk_id:uuid,
  locator:z.strictObject({kind:z.enum(["section","page"]),value:z.union([z.string().min(1).max(160),z.number().int().min(1).max(200)])}),
  source_url:z.string().regex(/^\/ai\/citations\/[0-9a-f-]{36}$/),
});
export const MappingDraft=z.strictObject({request_id:uuid,raw_sha256:z.string().regex(/^[a-f0-9]{64}$/),
  declared_encoding:z.enum(["UTF-8","CP932"]),selected_vendor_code:z.string().min(1).max(32),vendor_code:z.string().min(1).max(32),
  vendor_verification:z.literal("bff_profile_snapshot"),status:z.enum(["suggested","needs_review","abstained"]),
  assignments:z.array(z.strictObject({source_header:Header,target_field:Target,uncertainty:z.enum(["low","medium","high","unknown"]),
    basis:z.enum(["rule","document","model"]),reason:z.string().min(1).max(500),citations:z.array(Citation).max(5)})).max(3),
  unmapped_headers:z.array(Header).max(100),missing_required_fields:z.array(Target).max(3),
  warnings:z.array(z.enum(["AMBIGUOUS_HEADER","UNMAPPED_HEADER","FRESHNESS_UNVERIFIED","VENDOR_NOT_VERIFIED","ENCODING_DECLARED","SOURCE_UNTRUSTED","RULE_BASELINE","MODEL_UNAVAILABLE","MODEL_OUTPUT_INVALID","BUSINESS_VALIDATION_REQUIRED","PROFILE_MISMATCH","SAMPLE_TRUNCATED","ROW_REVIEW_REQUIRED","NO_AUTHORIZED_SOP"])).max(14),
  reason:z.string().min(1).max(500),profile_differences:z.array(z.strictObject({profile_id:z.string().max(48),version:z.number().int().min(1),active:z.boolean(),encoding_matches:z.boolean(),
    changes:z.partialRecord(Target,z.tuple([Header,Header.nullable()]))})).length(1),requires_human_acceptance:z.literal(true),provider_mode:z.enum(["fake","local","live"]),
}).refine(d=>{
  const fields=d.assignments.map(a=>a.target_field),headers=d.assignments.map(a=>a.source_header);
  return new Set(fields).size===fields.length&&new Set(headers).size===headers.length&&
    (d.status!=="suggested"||fields.length===3)&&(d.status!=="abstained"||fields.length===0)&&
    JSON.stringify(d.missing_required_fields)===JSON.stringify(["sku","name","on_hand"].filter(t=>!fields.includes(t as z.infer<typeof Target>)))&&
    new Set(d.unmapped_headers).size===d.unmapped_headers.length&&!d.unmapped_headers.some(h=>headers.includes(h));
});
export const MappingOutput=z.strictObject({draft:MappingDraft,evidence:z.array(z.strictObject({citation:Citation,excerpt:z.string().min(1).max(2000)})).max(2)});
export type Draft=z.infer<typeof MappingDraft>;

export const Definition=z.strictObject({profileId:z.string().regex(/^[a-z][a-z0-9-]{0,47}$/),version:z.number().int().min(1).max(2147483647),
  encoding:z.enum(["UTF-8","CP932"]),vendorCode:z.string().min(1).max(32),mapping:Columns,skuNormalization:z.enum(["TRIM","TRIM_UPPER"])});
export const ProfileSummary=z.object({...Definition.shape,active:z.boolean()});
export type DefinitionInput=z.infer<typeof Definition>;
export type ProfileValue=z.infer<typeof ProfileSummary>;
export type ClientResult<T>={success:true;data:T}|{success:false;code:"INPUT_INVALID"|"REQUEST_FAILED"|"OUTCOME_UNKNOWN"|"OUTPUT_INVALID"};
export interface ProfileDraftSpec {
  publish(definition:DefinitionInput):Promise<ClientResult<ProfileValue>>;
  activate(profile:ProfileValue):Promise<ClientResult<ProfileValue>>;
}
export const MappingView=MappingOutput.extend({source_profile:z.strictObject({profile_id:z.string().max(48),version:z.number().int().min(1),vendor_code:z.string().max(32),encoding:z.enum(["UTF-8","CP932"]),mapping:Columns,sku_normalization:z.enum(["TRIM","TRIM_UPPER"]),active:z.literal(true),required_fields:z.tuple([z.literal("sku"),z.literal("name"),z.literal("on_hand")])})});
export type MappingViewValue=z.infer<typeof MappingView>;
