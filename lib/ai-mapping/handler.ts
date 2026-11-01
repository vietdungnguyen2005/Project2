import { createHash, createHmac, randomUUID } from "node:crypto";
import { z } from "zod";
import { JavaSession,readBounded } from "../ai-gateway/io";
import { Profile } from "../ai-gateway/projection";
import { signedRequest } from "../ai-gateway/transport";
import { actorRateLimit } from "../ai-gateway/limits";
import type { AgentGatewaySpec,GatewayFailure,Result } from "../ai-gateway/spec";
import { MappingInput,MappingOutput } from "./spec";

const scopes:Record<string,string>={NAMI:"ed76d914-7c66-5102-9178-676334063938",RIVERBYTE:"4aa83e9f-0a0a-5558-8f5e-0f87db27c923"};
const fail=(code:GatewayFailure,status:number):Result<never>=>({success:false,code,status});
export class MappingGatewayHandler implements AgentGatewaySpec {
  readonly requestId=randomUUID();readonly cookies:string[]=[];
  async execute(request:Request):Promise<Result<unknown>>{
    if(process.env.AI_COPILOT_ENABLED!=="true"||process.env.AI_MAPPING_DRAFTS_ENABLED!=="true")return fail("AI_DISABLED",503);
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
    try{input=MappingInput.parse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(request,6700000,signal))));}
    catch(error){return fail("INPUT_INVALID",error instanceof Error&&error.message==="BODY_LIMIT"?413:400);}
    const raw=Buffer.from(input.raw_base64,"base64");
    if(!raw.length||raw.length>5000000||raw.toString("base64")!==input.raw_base64)return fail("INPUT_INVALID",400);
    const session=new JavaSession(request,signal,this.requestId);
    try{
      const auth=await session.check(undefined,true);if(!auth.success)return auth;
      const key=process.env.AI_ACTOR_HMAC_KEY??"",origin=process.env.AI_SERVICE_ORIGIN?.replace(/\/$/,"");
      if(Buffer.byteLength(key)<32||!origin||!/^https?:\/\//.test(origin))return fail("CONFIGURATION_UNAVAILABLE",503);
      const actor=createHmac("sha256",key).update("vmarket-ai-actor:v1\n"+auth.data).digest("hex");
      if(!actorRateLimit.consume(actor))return fail("BUDGET_EXCEEDED",429);
      const readProfile=async()=>{
        const response=await session.get("/api/ops/integration-profiles");
        if(!response.ok){void response.body?.cancel();return null;}
        const profiles=z.array(Profile).max(100).parse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,5000000,signal))));
        return profiles.find(p=>p.profile_id===input.source_profile_id&&p.version===input.source_profile_version&&p.active&&p.encoding===input.declared_encoding)??null;
      };
      const profile=await readProfile();if(!profile)return fail(session.authorizationStatus?"ACCESS_DENIED":"STEP_CONFLICT",session.authorizationStatus??409);
      const scope=scopes[profile.vendor_code];
      const configured=process.env.AI_COLLECTION_IDS_JSON?JSON.parse(process.env.AI_COLLECTION_IDS_JSON) as unknown:Object.values(scopes);
      if(!Array.isArray(configured)||configured.some(s=>!Object.values(scopes).includes(s)))return fail("CONFIGURATION_UNAVAILABLE",503);
      if(!scope||!configured.includes(scope))return fail("ACCESS_DENIED",403);
      const {sku_normalization:_normalization,required_fields:_fields,...candidate}=profile;
      const signed=signedRequest({actor_ref:actor,request_id:this.requestId,run_id:randomUUID(),step:0,collection_ids:[scope],deadline_at:new Date(deadline).toISOString()},"/ai/mapping/suggest",{
        input:{raw_base64:input.raw_base64,declared_encoding:input.declared_encoding,selected_vendor_code:profile.vendor_code,profile_ids:[profile.profile_id],method:input.method,synthetic:true},profile_candidates:[candidate],
      });
      signal.throwIfAborted();
      const response=await fetch(origin+"/ai/mapping/suggest",{method:"POST",...signed,signal,redirect:"error",cache:"no-store"});
      if(!response.ok){void response.body?.cancel();const status=[400,401,403,409,413,429,504].includes(response.status)?response.status:503;return fail(status===504?"DEADLINE_EXCEEDED":status===401||status===403?"ACCESS_DENIED":status===409?"STEP_CONFLICT":status===400||status===413?"INPUT_INVALID":"DEPENDENCY_UNAVAILABLE",status);}
      const result=MappingOutput.safeParse(JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(await readBounded(response,65536,signal))));
      if(!result.success)return fail("OUTPUT_INVALID",502);
      const draft=result.data.draft,diff=draft.profile_differences[0];
      if(draft.request_id!==this.requestId||draft.raw_sha256!==createHash("sha256").update(raw).digest("hex")||draft.declared_encoding!==profile.encoding||draft.vendor_code!==profile.vendor_code||draft.selected_vendor_code!==profile.vendor_code||diff.profile_id!==profile.profile_id||diff.version!==profile.version||!diff.active||!diff.encoding_matches)return fail("OUTPUT_INVALID",502);
      const finalAuth=await session.check(auth.data,true);if(!finalAuth.success)return finalAuth;
      const current=await readProfile();if(!current||JSON.stringify(current)!==JSON.stringify(profile))return fail(session.authorizationStatus?"ACCESS_DENIED":"STEP_CONFLICT",session.authorizationStatus??409);
      signal.throwIfAborted();
      return {success:true,data:{...result.data,source_profile:profile}};
    }finally{this.cookies.push(...session.refreshedCookies);}
  }
}

export async function handleMapping(request:Request):Promise<Response>{
  const handler=new MappingGatewayHandler(),result=await handler.execute(request);
  const headers=new Headers({"Cache-Control":"no-store","X-Request-ID":handler.requestId});
  for(const cookie of handler.cookies)headers.append("Set-Cookie",cookie);
  return Response.json(result.success?result.data:{code:result.code},{status:result.success?200:result.status,headers});
}
