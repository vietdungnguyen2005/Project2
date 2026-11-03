import { createHmac,randomUUID } from "node:crypto";
import { JavaSession,readBounded } from "../ai-gateway/io";
import { ActorRateLimit } from "../ai-gateway/limits";
import { signedRequest,type TransportContext } from "../ai-gateway/transport";
import type { AgentGatewaySpec,GatewayFailure,Result } from "../ai-gateway/spec";
import { RunInput,RunSnapshot,type Snapshot } from "./spec";
import { executeRead,type Observation } from "../ai-gateway/tools";
import { KnowledgeData } from "../ai-gateway/knowledge";

const SCOPES=["ed76d914-7c66-5102-9178-676334063938","4aa83e9f-0a0a-5558-8f5e-0f87db27c923"];
const rate=new ActorRateLimit(20);
const fail=(code:GatewayFailure,status:number):Result<never>=>({success:false,code,status});
class TransportFailure extends Error {constructor(readonly code:GatewayFailure,readonly status:number){super(code);}}

export class RunHandler implements AgentGatewaySpec {
  readonly requestId=randomUUID();readonly cookies:string[]=[];
  async execute(request:Request):Promise<Result<unknown>>{
    if(process.env.AI_COPILOT_ENABLED!=="true"||process.env.AI_RUNS_ENABLED!=="true")return fail("AI_DISABLED",503);
    const controller=new AbortController(),signal=AbortSignal.any([request.signal,controller.signal]);
    const deadline=Date.now()+25000,timer=setTimeout(()=>controller.abort(),25000);
    let abort=()=>{};
    try{return await Promise.race([this.run(request,signal,deadline),new Promise<Result<never>>(resolve=>{
      abort=()=>resolve(fail("DEADLINE_EXCEEDED",request.signal.aborted?499:504));signal.addEventListener("abort",abort,{once:true});if(signal.aborted)abort();
    })]);}catch(error){return error instanceof TransportFailure?fail(error.code,error.status):fail(signal.aborted?"DEADLINE_EXCEEDED":"DEPENDENCY_UNAVAILABLE",signal.aborted?504:503);}
    finally{clearTimeout(timer);signal.removeEventListener("abort",abort);controller.abort();}
  }
  private async run(request:Request,signal:AbortSignal,deadline:number):Promise<Result<unknown>>{
    const url=new URL(request.url);if(request.headers.get("host"))url.host=request.headers.get("host")!;
    if(request.method!=="POST"||request.headers.get("origin")!==url.origin||request.headers.get("sec-fetch-site")==="cross-site")return fail("ACCESS_DENIED",403);
    let input;
    try{input=RunInput.parse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(request,65536,signal))));}
    catch{return fail("INPUT_INVALID",400);}
    const session=new JavaSession(request,signal,this.requestId);
    try{
      const auth=await session.check(undefined,true);if(!auth.success)return auth;
      const key=process.env.AI_ACTOR_HMAC_KEY??"",origin=process.env.AI_SERVICE_ORIGIN?.replace(/\/$/,"");
      if(Buffer.byteLength(key)<32||!origin||!/^https?:\/\//.test(origin))return fail("CONFIGURATION_UNAVAILABLE",503);
      const configured=process.env.AI_COLLECTION_IDS_JSON?JSON.parse(process.env.AI_COLLECTION_IDS_JSON) as unknown:SCOPES;
      if(!Array.isArray(configured)||configured.length>10||new Set(configured).size!==configured.length||configured.some(s=>!SCOPES.includes(s)))return fail("CONFIGURATION_UNAVAILABLE",503);
      const scopes=input.action==="start"?(input.collection_ids??configured as string[]):configured as string[];
      if(new Set(scopes).size!==scopes.length||scopes.some(s=>!configured.includes(s)))return fail("ACCESS_DENIED",403);
      const actor=createHmac("sha256",key).update("vmarket-ai-actor:v1\n"+auth.data).digest("hex");
      if(!rate.consume(actor))return fail("BUDGET_EXCEEDED",429);
      const context:TransportContext={actor_ref:actor,request_id:this.requestId,run_id:input.action==="start"?randomUUID():input.run_id,step:0,collection_ids:scopes,deadline_at:new Date(deadline).toISOString()};
      let bound:Snapshot|undefined;
      const control=async(payload:Record<string,unknown>,step=0):Promise<Snapshot>=>{
        signal.throwIfAborted();context.step=step;
        const signed=signedRequest(context,"/ai/runs/control",payload);
        const response=await fetch(origin+"/ai/runs/control",{method:"POST",...signed,signal,redirect:"manual",cache:"no-store"});
        if(!response.ok){void response.body?.cancel();const codes:Partial<Record<number,GatewayFailure>>={400:"INPUT_INVALID",401:"ACCESS_DENIED",403:"ACCESS_DENIED",404:"RUN_NOT_AVAILABLE",409:"STEP_CONFLICT",413:"INPUT_INVALID",429:"BUDGET_EXCEEDED",502:"OUTPUT_INVALID",504:"DEADLINE_EXCEEDED"};throw new TransportFailure(codes[response.status]??"DEPENDENCY_UNAVAILABLE",codes[response.status]?response.status:503);}
        const parsed=RunSnapshot.safeParse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,16384,signal))));
        if(!parsed.success||parsed.data.state.run_id!==context.run_id||parsed.data.collection_ids.some(s=>!scopes.includes(s)))throw new TransportFailure("OUTPUT_INVALID",502);
        if(bound&&(parsed.data.state.conversation_id!==bound.state.conversation_id||parsed.data.state.initial_request_id!==bound.state.initial_request_id||JSON.stringify(parsed.data.collection_ids)!==JSON.stringify(bound.collection_ids)))throw new TransportFailure("OUTPUT_INVALID",502);
        if(typeof payload.expected_revision==="number"&&parsed.data.state.revision!==payload.expected_revision+(payload.action==="plan"?2:1))throw new TransportFailure("OUTPUT_INVALID",502);
        bound=parsed.data;
        return parsed.data;
      };
      let snapshot:Snapshot;
      const observations:Observation[]=[];
      if(input.action==="start"){
        const conversation=randomUUID();
        snapshot=await control({action:"start",question:input.question,collection_ids:scopes,conversation_id:conversation});
        if(snapshot.state.conversation_id!==conversation||snapshot.state.revision!==0||snapshot.state.status!=="started"||snapshot.state.phase!=="idle"||snapshot.state.step!==0||snapshot.state.initial_request_id!==this.requestId||snapshot.state.observations.length)throw new TransportFailure("OUTPUT_INVALID",502);
      }else{
        snapshot=await control({action:"inspect"});
        if(input.action!=="inspect"){
          if(snapshot.state.revision!==input.expected_revision)return fail("STEP_CONFLICT",409);
          if(input.action==="cancel")snapshot=await control({action:"cancel",expected_revision:input.expected_revision},snapshot.state.step);
          else {
            if(!snapshot.state.can_resume)return fail("STEP_CONFLICT",409);
            if(snapshot.state.status==="waiting_for_operator")snapshot=await control({action:"recover",expected_revision:snapshot.state.revision},snapshot.state.step);
            let finished=false;
            for(let iteration=0;iteration<12;iteration++){
              if(snapshot.state.status!=="started"){finished=true;break;}
              signal.throwIfAborted();const checked=await session.check(auth.data,true);if(!checked.success)return checked;
              if(snapshot.state.phase==="idle"){
                const previous=snapshot;
                snapshot=await control({action:"plan",expected_revision:snapshot.state.revision},snapshot.state.step);
                if(snapshot.state.step!==previous.state.step||snapshot.state.observations.length!==previous.state.observations.length)throw new TransportFailure("OUTPUT_INVALID",502);
                if(snapshot.state.status==="started"&&snapshot.state.phase!=="ready")throw new TransportFailure("OUTPUT_INVALID",502);
                continue;
              }
              if(snapshot.state.phase!=="ready"||!snapshot.pending_intent||snapshot.state.step>=3)return fail("STEP_CONFLICT",409);
              const intent=snapshot.pending_intent;
              if(snapshot.state.observations.some(o=>o.call_id===intent.call_id))return fail("STEP_CONFLICT",409);
              if("job_id" in intent.arguments&&!snapshot.known_job_ids.includes(intent.arguments.job_id))return fail("OUTPUT_INVALID",502);
              if(intent.tool_name==="search_supplier_knowledge"&&intent.arguments.collection_ids.some(s=>!snapshot.collection_ids.includes(s)))return fail("ACCESS_DENIED",403);
              const ready=snapshot;
              snapshot=await control({action:"claim",expected_revision:ready.state.revision},ready.state.step);
              if(snapshot.state.status!=="started"||snapshot.state.phase!=="executing"||!snapshot.lease_id||snapshot.pending_intent?.call_id!==intent.call_id||snapshot.state.step!==ready.state.step)throw new TransportFailure("OUTPUT_INVALID",502);
              const current=await session.check(auth.data,true);if(!current.success)return current;
              let observed:Observation;
              if(intent.tool_name==="search_supplier_knowledge"){
                context.step=snapshot.state.step;
                const signed=signedRequest(context,"/ai/knowledge/search",intent.arguments);
                const response=await fetch(origin+"/ai/knowledge/search",{method:"POST",...signed,signal,cache:"no-store",redirect:"manual"});
                if(!response.ok){void response.body?.cancel();throw new TransportFailure(response.status===401||response.status===403?"ACCESS_DENIED":"DEPENDENCY_UNAVAILABLE",response.status===401||response.status===403?response.status:503);}
                const result=KnowledgeData.safeParse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,16384,signal))));
                if(!result.success)throw new TransportFailure("OUTPUT_INVALID",502);
                observed={call_id:intent.call_id,tool_name:intent.tool_name,status:"ok",observed_at:new Date().toISOString(),truncated:false,error_code:null,data:result.data};
                if(Buffer.byteLength(JSON.stringify(observed))>16384)observed={...observed,status:"invalid",error_code:"TOOL_RESULT_TOO_LARGE",data:null};
              }else observed=await executeRead(intent,session);
              if(observed.status==="denied")return fail("ACCESS_DENIED",session.authorizationStatus??403);
              const afterRead=await session.check(auth.data,true);if(!afterRead.success)return afterRead;
              const leased=snapshot;
              snapshot=await control({action:"record",expected_revision:leased.state.revision,lease_id:leased.lease_id,observation:observed},leased.state.step);
              const summary=snapshot.state.observations.at(-1);
              if(snapshot.state.step!==leased.state.step+1||snapshot.state.phase!=="idle"||snapshot.state.observations.length!==leased.state.observations.length+1||summary?.call_id!==observed.call_id||summary.status!==observed.status)throw new TransportFailure("OUTPUT_INVALID",502);
              observations.push(observed);
            }
            if(!finished&&snapshot.state.status==="started")return fail("BUDGET_EXCEEDED",429);
          }
        }
      }
      const finalAuth=await session.check(auth.data,true);if(!finalAuth.success)return finalAuth;
      if(snapshot.state.outcome?.status==="answered"&&(!snapshot.state.observations.length||snapshot.state.observations.some(o=>o.status!=="ok")))return fail("OUTPUT_INVALID",502);
      return {success:true,data:{run:snapshot.state,observations}};
    }finally{this.cookies.push(...session.refreshedCookies);}
  }
}

export async function handleRun(request:Request):Promise<Response>{
  const handler=new RunHandler(),result=await handler.execute(request),headers=new Headers({"Cache-Control":"no-store","X-Request-ID":handler.requestId});
  for(const cookie of handler.cookies)headers.append("Set-Cookie",cookie);
  return Response.json(result.success?result.data:{code:result.code},{status:result.success?200:result.status,headers});
}
