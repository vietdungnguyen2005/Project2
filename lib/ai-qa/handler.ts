import { createHmac, randomUUID } from "node:crypto";
import { z } from "zod";
import { JavaSession, readBounded } from "../ai-gateway/io";
import { Profile } from "../ai-gateway/projection";
import { signedRequest } from "../ai-gateway/transport";
import { actorRateLimit } from "../ai-gateway/limits";
import type { AgentGatewaySpec, GatewayFailure, Result } from "../ai-gateway/spec";
import { AskInput, SourceInput, WireAnswer, WireSource } from "./spec";

const scopes:Record<string,string>={NAMI:"ed76d914-7c66-5102-9178-676334063938",RIVERBYTE:"4aa83e9f-0a0a-5558-8f5e-0f87db27c923"};
const fail=(code:GatewayFailure,status:number):Result<never>=>({success:false,code,status});
export class PolicyGatewayHandler implements AgentGatewaySpec {
  readonly requestId=randomUUID();readonly cookies:string[]=[];
  constructor(private action:"ask"|"source"){}
  async execute(request:Request):Promise<Result<unknown>>{
    if(process.env.AI_COPILOT_ENABLED!=="true"||process.env.AI_QA_ENABLED!=="true")return fail("AI_DISABLED",503);
    const controller=new AbortController(),signal=AbortSignal.any([request.signal,controller.signal]);
    const deadline=Date.now()+25000,timer=setTimeout(()=>controller.abort(),25000);
    let abort=()=>{};
    try{return await Promise.race([this.run(request,signal,deadline),new Promise<Result<never>>(resolve=>{
      abort=()=>resolve(fail("DEADLINE_EXCEEDED",request.signal.aborted?499:504));
      signal.addEventListener("abort",abort,{once:true});if(signal.aborted)abort();
    })]);}catch{return fail(signal.aborted?"DEADLINE_EXCEEDED":"DEPENDENCY_UNAVAILABLE",signal.aborted?504:503);}
    finally{clearTimeout(timer);signal.removeEventListener("abort",abort);controller.abort();}
  }
  private async run(request:Request,signal:AbortSignal,deadline:number):Promise<Result<unknown>>{
    const url=new URL(request.url);if(request.headers.get("host"))url.host=request.headers.get("host")!;
    if(request.method!=="POST"||request.headers.get("origin")!==url.origin||request.headers.get("sec-fetch-site")==="cross-site")return fail("ACCESS_DENIED",403);
    let input;
    try{const raw=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(request,16384,signal)));
      input=this.action==="ask"?AskInput.parse(raw):SourceInput.parse(raw);
    }catch{return fail("INPUT_INVALID",400);}
    const session=new JavaSession(request,signal,this.requestId);
    try{
      const auth=await session.check(undefined,true);if(!auth.success)return auth;
      const key=process.env.AI_ACTOR_HMAC_KEY??"",origin=process.env.AI_SERVICE_ORIGIN?.replace(/\/$/,"");
      if(Buffer.byteLength(key)<32||!origin||!/^https?:\/\//.test(origin))return fail("CONFIGURATION_UNAVAILABLE",503);
      const actor=createHmac("sha256",key).update("vmarket-ai-actor:v1\n"+auth.data).digest("hex");
      if(!actorRateLimit.consume(actor))return fail("BUDGET_EXCEEDED",429);
      const readProfile=async()=>{
        const response=await session.get("/api/ops/integration-profiles");
        if(!response.ok){void response.body?.cancel();return {success:false as const,status:response.status};}
        const profiles=z.array(Profile).max(100).parse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,5000000,signal))));
        return {success:true as const,profile:profiles.find(p=>p.profile_id===input.profile_id&&p.version===input.profile_version&&p.active)};
      };
      const initial=await readProfile();
      if(!initial.success)return fail(initial.status===401||initial.status===403?"ACCESS_DENIED":"DEPENDENCY_UNAVAILABLE",initial.status===401||initial.status===403?initial.status:503);
      const profile=initial.profile;if(!profile)return fail("STEP_CONFLICT",409);
      const scope=scopes[profile.vendor_code];
      const configured=process.env.AI_COLLECTION_IDS_JSON?JSON.parse(process.env.AI_COLLECTION_IDS_JSON) as unknown:Object.values(scopes);
      if(!Array.isArray(configured)||new Set(configured).size!==configured.length||configured.some(s=>!Object.values(scopes).includes(s)))return fail("CONFIGURATION_UNAVAILABLE",503);
      if(!scope||!configured.includes(scope))return fail("ACCESS_DENIED",403);
      const path=this.action==="ask"?"/ai/ask":"/ai/citations/read";
      const payload="question" in input?{question:input.question,language:input.language,collection_ids:[scope]}:{chunk_id:input.chunk_id,collection_ids:[scope]};
      const signed=signedRequest({actor_ref:actor,request_id:this.requestId,run_id:randomUUID(),step:0,collection_ids:[scope],deadline_at:new Date(deadline).toISOString()},path,payload);
      signal.throwIfAborted();
      const response=await fetch(origin+path,{method:"POST",...signed,signal,redirect:"manual",cache:"no-store"});
      if(!response.ok){void response.body?.cancel();const codes:Partial<Record<number,GatewayFailure>>={400:"INPUT_INVALID",401:"ACCESS_DENIED",403:"ACCESS_DENIED",404:"DOCUMENT_NOT_AVAILABLE",409:"STEP_CONFLICT",413:"INPUT_INVALID",429:"BUDGET_EXCEEDED",502:"OUTPUT_INVALID",504:"DEADLINE_EXCEEDED"};return fail(codes[response.status]??"DEPENDENCY_UNAVAILABLE",codes[response.status]?response.status:503);}
      let result;
      try{const raw=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,65536,signal)));
        result=this.action==="ask"?WireAnswer.parse(raw):WireSource.parse(raw);
      }catch{return fail("OUTPUT_INVALID",502);}
      if(result.request_id!==this.requestId||("chunk_id" in input&&"citation" in result&&result.citation.chunk_id!==input.chunk_id))return fail("OUTPUT_INVALID",502);
      const checked=await session.check(auth.data,true);if(!checked.success)return checked;
      const current=await readProfile();
      if(!current.success)return fail(current.status===401||current.status===403?"ACCESS_DENIED":"DEPENDENCY_UNAVAILABLE",current.status===401||current.status===403?current.status:503);
      if(!current.profile||JSON.stringify(current.profile)!==JSON.stringify(profile))return fail("STEP_CONFLICT",409);
      signal.throwIfAborted();return {success:true,data:result};
    }finally{this.cookies.push(...session.refreshedCookies);}
  }
}
export async function handleQA(request:Request,action:"ask"|"source"):Promise<Response>{
  const handler=new PolicyGatewayHandler(action),result=await handler.execute(request);
  const headers=new Headers({"Cache-Control":"no-store","X-Request-ID":handler.requestId});
  for(const cookie of handler.cookies)headers.append("Set-Cookie",cookie);
  return Response.json(result.success?result.data:{code:result.code},{status:result.success?200:result.status,headers});
}
