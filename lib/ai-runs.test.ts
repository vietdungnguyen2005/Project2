import { afterEach,expect,it,vi } from "vitest";
import { handleRun } from "./ai-runs/handler";
import { RunInput,type Snapshot } from "./ai-runs/spec";
import { generateKeyPairSync,verify } from "node:crypto";

function setup(){
  const key=generateKeyPairSync("ed25519"),actor=crypto.randomUUID();
  for(const [k,v] of Object.entries({AI_COPILOT_ENABLED:"true",AI_RUNS_ENABLED:"true",BACKEND_ORIGIN:"http://java",AI_SERVICE_ORIGIN:"http://ai",AI_SIGNING_KID:"test",AI_ACTOR_HMAC_KEY:"r".repeat(64),AI_SIGNING_KEY_PKCS8_BASE64:key.privateKey.export({format:"der",type:"pkcs8"}).toString("base64")}))vi.stubEnv(k,v);
  const seen:{url:string;init:RequestInit}[]=[];
  const faults={revoked:false,wrongTool:false};
  let snapshot:Snapshot;
  vi.stubGlobal("fetch",vi.fn(async(url:string,init:RequestInit)=>{
    seen.push({url,init});
    if(url.endsWith("/api/auth/session"))return Response.json({authenticated:!faults.revoked,username:actor,roles:["ROLE_OPERATOR"],csrfToken:"current"});
    if(url.endsWith("/api/ops/imports"))return Response.json([]);
    const headers=new Headers(init.headers),encoded=headers.get("X-AI-Context")!;
    expect(verify(null,Buffer.from("vmarket-ai-context:v1."+encoded),key.publicKey,Buffer.from(headers.get("X-AI-Signature")!,"base64url"))).toBe(true);
    const claims=JSON.parse(Buffer.from(encoded,"base64url").toString()),body=JSON.parse(init.body as string);
    expect(claims.operation).toBe("run_control");
    if(body.action==="start")snapshot={state:{run_id:claims.run_id,conversation_id:body.conversation_id,initial_request_id:claims.request_id,status:"started",phase:"idle",revision:0,step:0,created_at:Math.floor(Date.now()/1000),updated_at:Math.floor(Date.now()/1000),expires_at:Math.floor(Date.now()/1000)+600,reason:null,observations:[],outcome:null,can_resume:true,can_cancel:true},collection_ids:claims.collection_ids,pending_intent:null,lease_id:null,known_job_ids:[]};
    if(body.action==="plan"){
      snapshot.state.revision+=2;
      if(snapshot.state.observations.length){snapshot.state.status="completed";snapshot.state.phase="terminal";snapshot.state.can_resume=false;snapshot.state.can_cancel=false;snapshot.state.outcome={kind:"final",status:"answered",handoff_reason:null};}
      else{snapshot.state.phase="ready";snapshot.pending_intent={call_id:crypto.randomUUID(),tool_name:"get_imports",arguments:{}};}
    }
    if(body.action==="claim"){snapshot.state.revision++;snapshot.state.phase="executing";snapshot.state.can_resume=false;snapshot.lease_id=crypto.randomUUID();}
    if(body.action==="record"){
      snapshot.state.revision++;snapshot.state.phase="idle";snapshot.state.step++;snapshot.state.can_resume=true;
      const o=body.observation;snapshot.state.observations.push({call_id:o.call_id,tool_name:o.tool_name,status:o.status,observed_at:o.observed_at,truncated:o.truncated,error_code:o.error_code,item_count:0});snapshot.pending_intent=null;snapshot.lease_id=null;
    }
    if(body.action==="cancel"){snapshot.state.revision++;snapshot.state.status="cancelled";snapshot.state.phase="terminal";snapshot.state.reason="OPERATOR_CANCELLED";snapshot.state.can_resume=false;snapshot.state.can_cancel=false;snapshot.pending_intent=null;snapshot.lease_id=null;}
    if(faults.wrongTool&&body.action==="plan")return Response.json({...snapshot,pending_intent:{call_id:crypto.randomUUID(),tool_name:"confirm_import",arguments:{}}});
    return Response.json(snapshot);
  }));
  return {seen,get:()=>snapshot,faults};
}
function request(body:unknown){return new Request("http://localhost/api/ops/ai/runs",{method:"POST",headers:{Origin:"http://localhost","X-CSRF-TOKEN":"current",Cookie:"VMARKET_SESSION=session"},body:JSON.stringify(body)});}

afterEach(()=>{vi.unstubAllGlobals();vi.unstubAllEnvs();});
it("disables durable commands before any session or provider access",async()=>{
  vi.stubEnv("AI_RUNS_ENABLED","false");const fetch=vi.fn();vi.stubGlobal("fetch",fetch);
  const response=await handleRun(new Request("http://localhost/api/ops/ai/runs",{method:"POST",body:"{}"}));
  expect(response.status).toBe(503);expect(fetch).not.toHaveBeenCalled();
});

it("resumes a stored revision with fresh auth and checkpoints one fixed read exactly once",async()=>{
  const {seen}=setup();const created=await(await handleRun(request({action:"start",question:"List imports"}))).json();
  const response=await handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}));
  expect(response.status).toBe(200);const data=await response.json();expect(data.run.status).toBe("completed");
  expect(data.run.observations).toHaveLength(1);
  expect(seen.filter(s=>s.url.endsWith("/api/ops/imports"))).toHaveLength(1);
  expect(seen.filter(s=>s.url.startsWith("http://java")).every(s=>s.init.method==="GET")).toBe(true);
  expect(JSON.stringify(data)).not.toContain("lease_id");
});

it("rejects a stale duplicate resume without repeating the read",async()=>{
  const {seen}=setup();const created=await(await handleRun(request({action:"start",question:"List imports"}))).json();
  expect((await handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}))).status).toBe(200);
  expect((await handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}))).status).toBe(409);
  expect(seen.filter(s=>s.url.endsWith("/api/ops/imports"))).toHaveLength(1);
});

it("rechecks current session after a pause and rejects expiry before any checkpoint or tool access",async()=>{
  const {seen,faults,get}=setup();const created=await(await handleRun(request({action:"start",question:"List imports"}))).json();
  const before=seen.length;faults.revoked=true;
  expect((await handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}))).status).toBe(401);
  expect(seen.slice(before).every(s=>s.url.endsWith("/api/auth/session"))).toBe(true);expect(get().state.revision).toBe(0);
});

it("rejects a write tool name in a restored planner response before dispatch",async()=>{
  const {seen,faults}=setup();const created=await(await handleRun(request({action:"start",question:"List imports"}))).json();faults.wrongTool=true;
  expect((await handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}))).status).toBe(502);
  expect(seen.some(s=>s.url.endsWith("/api/ops/imports"))).toBe(false);
});

it("aborts an in-flight read at the request deadline without recording or retrying it",async()=>{
  vi.useFakeTimers();
  try{
    const {seen,get}=setup(),original=global.fetch;
    const created=await(await handleRun(request({action:"start",question:"List imports"}))).json();
    vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{
      if(url.endsWith("/api/ops/imports"))return new Promise<Response>((_resolve,reject)=>init.signal!.addEventListener("abort",()=>reject(new DOMException("Aborted","AbortError")),{once:true}));
      return original(url,init);
    });
    const pending=handleRun(request({action:"resume",run_id:created.run.run_id,expected_revision:0}));
    await vi.advanceTimersByTimeAsync(25001);
    expect((await pending).status).toBe(504);expect(get().state.phase).toBe("executing");
    expect(seen.filter(s=>s.url==="http://ai/ai/runs/control").map(s=>JSON.parse(s.init.body as string).action)).not.toContain("record");
  }finally{vi.useRealTimers();}
});

it("persists a new server-owned conversation/run before any model or tool is executed",async()=>{
  const {seen}=setup();const response=await handleRun(request({action:"start",question:"List imports"}));
  expect(response.status).toBe(200);const body=await response.json();expect(body.run.status).toBe("started");expect(body.run.revision).toBe(0);
  expect(seen.filter(s=>s.url==="http://ai/ai/runs/control")).toHaveLength(1);
  expect(seen.filter(s=>s.url.startsWith("http://java")).every(s=>s.url.endsWith("/api/auth/session"))).toBe(true);
  const wire=seen.find(s=>s.url.startsWith("http://ai"))!;expect(new Headers(wire.init.headers).has("Cookie")).toBe(false);
  expect(JSON.stringify(body)).not.toContain("lease_id");expect(JSON.stringify(body)).not.toContain("question");
});

it("accepts only an explicit revision-bound resume, not tool results or approval authority",()=>{
  const input={action:"resume",run_id:crypto.randomUUID(),expected_revision:0};
  expect(RunInput.safeParse(input).success).toBe(true);
  for(const extra of [{observation:{}},{actor_ref:"admin"},{approved:true},{question:"publish profile"}])
    expect(RunInput.safeParse({...input,...extra}).success).toBe(false);
  expect(RunInput.safeParse({action:"resume",run_id:input.run_id}).success).toBe(false);
});
