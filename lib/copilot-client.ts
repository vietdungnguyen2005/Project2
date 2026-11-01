import { z } from "zod";
import { FailureCode, uuid, type Result } from "./ai-gateway/spec";
import { getOperatorSession } from "./operator-session";

const paths={ask:"/api/ops/ai/ask",source:"/api/ops/ai/citations/read",run:"/api/ops/ai/runs"} as const;
export type ClientReply<T>=Result<T>&{request_id?:string};
export function refreshOperatorAccess(){if(typeof window!=="undefined")window.dispatchEvent(new Event("vmarket-session-check"));}
export async function copilotRequest<T>(action:keyof typeof paths,input:unknown,schema:z.ZodType<T>,signal:AbortSignal):Promise<ClientReply<T>>{
  let request_id:string|undefined;
  const timeout=AbortSignal.timeout(30000),combined=AbortSignal.any([signal,timeout]);
  try{
    const session=await getOperatorSession(combined);
    if(!session.authenticated||!session.roles.includes("ROLE_OPERATOR")){
      refreshOperatorAccess();return {success:false,code:"SESSION_EXPIRED",status:401};
    }
    const response=await fetch(paths[action],{method:"POST",headers:{"Content-Type":"application/json","X-CSRF-TOKEN":session.csrfToken},body:JSON.stringify(input),cache:"no-store",signal:combined});
    const trace=uuid.safeParse(response.headers.get("X-Request-ID"));if(trace.success)request_id=trace.data;
    if(response.status===401||response.status===403){
      void response.body?.cancel().catch(()=>undefined);refreshOperatorAccess();
      return {success:false,code:response.status===401?"SESSION_EXPIRED":"ACCESS_DENIED",status:response.status,request_id};
    }
    const reader=response.body?.getReader();if(!reader)return {success:false,code:"OUTPUT_INVALID",status:502,request_id};
    const chunks:Uint8Array[]=[];let size=0;
    const cancel=()=>{void reader.cancel().catch(()=>undefined);};combined.addEventListener("abort",cancel,{once:true});
    try{while(true){combined.throwIfAborted();const next=await reader.read();combined.throwIfAborted();if(next.done)break;
      size+=next.value.length;if(size>65536){cancel();return {success:false,code:"OUTPUT_INVALID",status:502,request_id};}chunks.push(next.value);
    }}finally{combined.removeEventListener("abort",cancel);reader.releaseLock();}
    const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
    const raw:unknown=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
    if(!response.ok){
      const error=z.object({code:FailureCode}).safeParse(raw);
      return {success:false,code:error.success?error.data.code:"DEPENDENCY_UNAVAILABLE",status:response.status,request_id};
    }
    const data=schema.safeParse(raw);
    return data.success?{success:true,data:data.data,request_id}:{success:false,code:"OUTPUT_INVALID",status:502,request_id};
  }catch{return {success:false,code:combined.aborted?"DEADLINE_EXCEEDED":"DEPENDENCY_UNAVAILABLE",status:combined.aborted?504:503,request_id};}
}
