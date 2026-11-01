import { afterEach, expect, it, vi } from "vitest";
import { generateKeyPairSync, verify } from "node:crypto";
import { handleQA } from "./ai-qa/handler";

const profile={profileId:"supplier-b",version:7,encoding:"UTF-8",vendorCode:"RIVERBYTE",mapping:{sku:"SKU",name:"ProductName",on_hand:"AvailableQuantity"},active:true,
  skuNormalization:"TRIM",requiredFields:["sku","name","on_hand"],validation:{sku:{type:"string",minLength:1,maxLength:64},name:{type:"string",minLength:1,maxLength:160},on_hand:{type:"integer",min:0,max:1000000}},normalization:{sku:"TRIM",name:"TRIM",on_hand:"TRIM_INTEGER"}};
const scope="4aa83e9f-0a0a-5558-8f5e-0f87db27c923";
const chunk=crypto.randomUUID();
function request(body:unknown={question:"Which encoding?",language:"en",profile_id:"supplier-b",profile_version:7}){
  return new Request("http://localhost/api/ops/ai/ask",{method:"POST",headers:{Origin:"http://localhost","Content-Type":"application/json","X-CSRF-TOKEN":"current",Cookie:"VMARKET_SESSION=session"},body:JSON.stringify(body)});
}
function setup(){
  const key=generateKeyPairSync("ed25519");
  for(const [k,v] of Object.entries({AI_COPILOT_ENABLED:"true",AI_QA_ENABLED:"true",BACKEND_ORIGIN:"http://java",AI_SERVICE_ORIGIN:"http://ai",AI_SIGNING_KID:"test",AI_ACTOR_HMAC_KEY:"x".repeat(64),AI_SIGNING_KEY_PKCS8_BASE64:key.privateKey.export({format:"der",type:"pkcs8"}).toString("base64")}))vi.stubEnv(k,v);
  const state={active:true,revoked:false,wrongId:false};
  const seen:{url:string;init:RequestInit}[]=[];
  vi.stubGlobal("fetch",vi.fn(async(url:string,init:RequestInit)=>{
    seen.push({url,init});
    if(url.endsWith("/api/auth/session"))return Response.json({authenticated:!state.revoked,username:"qa-"+chunk,roles:["ROLE_OPERATOR"],csrfToken:"current"});
    if(url.endsWith("/api/ops/integration-profiles"))return Response.json([{...profile,active:state.active}]);
    const headers=new Headers(init.headers),encoded=headers.get("X-AI-Context")!;
    expect(verify(null,Buffer.from("vmarket-ai-context:v1."+encoded),key.publicKey,Buffer.from(headers.get("X-AI-Signature")!,"base64url"))).toBe(true);
    const claims=JSON.parse(Buffer.from(encoded,"base64url").toString());
    const citation={document_id:chunk,version_id:chunk,chunk_id:chunk,locator:{kind:"section",value:"encoding"},source_url:"/ai/citations/"+chunk};
    return Response.json(url.endsWith("/ai/ask")?{request_id:state.wrongId?crypto.randomUUID():claims.request_id,status:"answered",answer:"Original bytes use UTF-8.",reason:null,citations:[citation],confidence:"low",provider_mode:"fake"}:{request_id:claims.request_id,citation,excerpt:"Original bytes use UTF-8.",truncated:false});
  }));
  return {seen,state};
}
afterEach(()=>{vi.unstubAllEnvs();vi.unstubAllGlobals();});

it("derives the QA scope from a current Java profile and rechecks authority before returning signed evidence",async()=>{
  const {seen}=setup();
  const response=await handleQA(request(),"ask");
  expect(response.status).toBe(200);
  const data=await response.json();expect(data.answer).toBe("Original bytes use UTF-8.");
  expect(data.citations[0].source_url).toBeUndefined();
  const ai=seen.filter(s=>s.url==="http://ai/ai/ask");expect(ai).toHaveLength(1);
  expect(JSON.parse(ai[0].init.body as string)).toEqual({question:"Which encoding?",language:"en",collection_ids:[scope]});
  const headers=new Headers(ai[0].init.headers);
  expect(headers.has("Cookie")).toBe(false);expect(headers.has("X-CSRF-TOKEN")).toBe(false);
  expect(JSON.parse(Buffer.from(headers.get("X-AI-Context")!,"base64url").toString()).operation).toBe("ask");
  expect(seen.filter(s=>s.url.endsWith("integration-profiles"))).toHaveLength(2);
});

it("opens sources only through a fresh signed profile read and never exposes service URLs",async()=>{
  const {seen}=setup();
  const response=await handleQA(request({chunk_id:chunk,profile_id:"supplier-b",profile_version:7}),"source");
  expect(response.status).toBe(200);
  expect((await response.json()).citation.source_url).toBeUndefined();
  const headers=new Headers(seen.find(s=>s.url.endsWith("/ai/citations/read"))!.init.headers);
  expect(JSON.parse(Buffer.from(headers.get("X-AI-Context")!,"base64url").toString()).operation).toBe("documents_read");
});

it("discards an answer when the Java session is revoked during inference",async()=>{
  const {state}=setup(),original=global.fetch;
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{const response=await original(url,init);if(url.endsWith("/ai/ask"))state.revoked=true;return response;});
  const response=await handleQA(request(),"ask");expect(response.status).toBe(401);
  expect(await response.json()).toEqual({code:"SESSION_EXPIRED"});
});

it("discards a source when its actual supplier profile is deactivated during the read",async()=>{
  const {state}=setup(),original=global.fetch;
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{const response=await original(url,init);if(url.endsWith("/ai/citations/read"))state.active=false;return response;});
  expect((await handleQA(request({chunk_id:chunk,profile_id:"supplier-b",profile_version:7}),"source")).status).toBe(409);
});

it("rejects browser authority, cross-origin requests and wrong correlation IDs",async()=>{
  const {state,seen}=setup();
  expect((await handleQA(request({question:"x",language:"en",profile_id:"supplier-b",profile_version:7,collection_ids:[scope]}),"ask")).status).toBe(400);
  const crossed=request();crossed.headers.set("Origin","https://evil.example");
  expect((await handleQA(crossed,"ask")).status).toBe(403);expect(seen).toHaveLength(0);
  state.wrongId=true;expect((await handleQA(request(),"ask")).status).toBe(502);
});

it("keeps both QA routes off without calling Java or a provider",async()=>{
  vi.stubEnv("AI_QA_ENABLED","false");const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
  expect((await handleQA(request(),"ask")).status).toBe(503);
  expect((await handleQA(request({chunk_id:chunk,profile_id:"supplier-b",profile_version:7}),"source")).status).toBe(503);
  expect(fetch).not.toHaveBeenCalled();
});
