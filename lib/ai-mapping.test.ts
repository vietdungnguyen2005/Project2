import { afterEach, expect, it, vi } from "vitest";
import { handleMapping } from "./ai-mapping/handler";
import { MappingInput } from "./ai-mapping/spec";
import { createHash, generateKeyPairSync, verify } from "node:crypto";

const columns={sku:"SKU",name:"ProductName",on_hand:"AvailableQuantity"};
const profile={profileId:"supplier-b",version:7,encoding:"UTF-8",vendorCode:"RIVERBYTE",mapping:columns,active:true,
  skuNormalization:"TRIM",requiredFields:["sku","name","on_hand"],validation:{sku:{type:"string",minLength:1,maxLength:64},
  name:{type:"string",minLength:1,maxLength:160},on_hand:{type:"integer",min:0,max:1000000}},normalization:{sku:"TRIM",name:"TRIM",on_hand:"TRIM_INTEGER"}};
const input={raw_base64:Buffer.from("SKU,ProductName,AvailableQuantity\nVM-002,Desk,60\n").toString("base64"),declared_encoding:"UTF-8",source_profile_id:"supplier-b",source_profile_version:7,method:"rule",synthetic:true};
function request(){return new Request("http://localhost/api/ops/ai/mapping/suggest",{method:"POST",headers:{Origin:"http://localhost","Content-Type":"application/json","X-CSRF-TOKEN":"current",Cookie:"VMARKET_SESSION=session"},body:JSON.stringify(input)});}

function setup(){
  const key=generateKeyPairSync("ed25519");
  for(const [k,v] of Object.entries({AI_COPILOT_ENABLED:"true",AI_MAPPING_DRAFTS_ENABLED:"true",BACKEND_ORIGIN:"http://java",AI_SERVICE_ORIGIN:"http://ai",AI_SIGNING_KID:"test",AI_ACTOR_HMAC_KEY:"x".repeat(64),AI_SIGNING_KEY_PKCS8_BASE64:key.privateKey.export({format:"der",type:"pkcs8"}).toString("base64")}))vi.stubEnv(k,v);
  const seen:{url:string;init:RequestInit}[]=[];
  const state={active:true,revoked:false,wrongHash:false};
  const actor=crypto.randomUUID();
  vi.stubGlobal("fetch",vi.fn(async(url:string,init:RequestInit)=>{
    seen.push({url,init});
    if(url.endsWith("/api/auth/session"))return Response.json({authenticated:!state.revoked,username:actor,roles:["ROLE_OPERATOR"],csrfToken:"current"});
    if(url.endsWith("/api/ops/integration-profiles"))return Response.json([{...profile,active:state.active}]);
    const signed=new Headers(init.headers),claims=JSON.parse(Buffer.from(signed.get("X-AI-Context")!,"base64url").toString());
    expect(verify(null,Buffer.from("vmarket-ai-context:v1."+signed.get("X-AI-Context")),key.publicKey,Buffer.from(signed.get("X-AI-Signature")!,"base64url"))).toBe(true);
    return Response.json({draft:{request_id:claims.request_id,raw_sha256:state.wrongHash?"a".repeat(64):createHash("sha256").update(Buffer.from(input.raw_base64,"base64")).digest("hex"),declared_encoding:"UTF-8",selected_vendor_code:"RIVERBYTE",vendor_code:"RIVERBYTE",vendor_verification:"bff_profile_snapshot",status:"suggested",assignments:Object.entries(columns).map(([target_field,source_header])=>({target_field,source_header,uncertainty:"medium",basis:"rule",reason:"Exact rule",citations:[]})),unmapped_headers:[],missing_required_fields:[],warnings:["BUSINESS_VALIDATION_REQUIRED"],reason:"RULE_BASELINE",profile_differences:[{profile_id:"supplier-b",version:7,active:true,encoding_matches:true,changes:{}}],requires_human_acceptance:true,provider_mode:"fake"},evidence:[]});
  }));
  return {seen,state};
}

afterEach(()=>{vi.unstubAllEnvs();vi.unstubAllGlobals();});
it("keeps the mapping gateway off before reading a session or calling any provider",async()=>{
  vi.stubEnv("AI_MAPPING_DRAFTS_ENABLED","false");
  const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
  const response=await handleMapping(new Request("http://localhost/api/ops/ai/mapping/suggest",{method:"POST"}));
  expect(response.status).toBe(503);expect(await response.json()).toEqual({code:"AI_DISABLED"});
  expect(fetch).not.toHaveBeenCalled();
});

it("derives vendor and current version from Java, signs only the draft read and rechecks authority",async()=>{
  const {seen}=setup();
  const response=await handleMapping(request());
  expect(response.status).toBe(200);
  const ai=seen.filter(s=>s.url==="http://ai/ai/mapping/suggest");expect(ai).toHaveLength(1);
  const payload=JSON.parse(ai[0].init.body as string);expect(payload.input.selected_vendor_code).toBe("RIVERBYTE");
  expect(payload.profile_candidates[0].version).toBe(7);
  const headers=new Headers(ai[0].init.headers);expect(headers.has("Cookie")).toBe(false);expect(headers.has("X-CSRF-TOKEN")).toBe(false);
  const claims=JSON.parse(Buffer.from(headers.get("X-AI-Context")!,"base64url").toString());expect(claims.operation).toBe("mapping_suggest");
  expect(claims.collection_ids).toEqual(["4aa83e9f-0a0a-5558-8f5e-0f87db27c923"]);
  expect(seen.filter(s=>s.url.endsWith("integration-profiles"))).toHaveLength(2);
  expect(seen.filter(s=>s.url.startsWith("http://java")).every(s=>s.init.method==="GET")).toBe(true);
});

it("rejects a wrong original-byte digest from the AI service",async()=>{
  const {state}=setup();state.wrongHash=true;
  expect((await handleMapping(request())).status).toBe(502);
});

it("discards a draft if the selected Java profile is deactivated during inference",async()=>{
  const {state}=setup();const original=global.fetch;
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{const response=await original(url,init);if(url.endsWith("/ai/mapping/suggest"))state.active=false;return response;});
  expect((await handleMapping(request())).status).toBe(409);
});

it("discards a draft after operator revocation rather than returning cached authority",async()=>{
  const {state}=setup();const original=global.fetch;
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{const response=await original(url,init);if(url.endsWith("/ai/mapping/suggest"))state.revoked=true;return response;});
  expect((await handleMapping(request())).status).toBe(401);
});

it("accepts only original bytes and a profile reference, never browser vendor or profile authority",()=>{
  const input={raw_base64:"YQ==",declared_encoding:"UTF-8",source_profile_id:"supplier-b",source_profile_version:1,method:"rule",synthetic:true};
  expect(MappingInput.safeParse(input).success).toBe(true);
  for(const extra of [{selected_vendor_code:"NAMI"},{profile_candidates:[]},{actor_ref:"admin"},{mapping:{sku:"x"}}])
    expect(MappingInput.safeParse({...input,...extra}).success).toBe(false);
});
