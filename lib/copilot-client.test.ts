import { afterEach, expect, it, vi } from "vitest";
import { z } from "zod";
import { copilotRequest } from "./copilot-client";

afterEach(()=>vi.unstubAllGlobals());
it("checks the current session and treats an invalid provider response as a typed failure without retry",async()=>{
  const calls:string[]=[];
  vi.stubGlobal("fetch",vi.fn(async(url:string,init?:RequestInit)=>{
    calls.push(url);
    if(url==="/api/auth/session")return Response.json({authenticated:true,roles:["ROLE_OPERATOR"],username:"operator",csrfToken:"current"});
    expect(new Headers(init?.headers).get("X-CSRF-TOKEN")).toBe("current");
    return Response.json({answer:"unexpected"});
  }));
  const result=await copilotRequest("ask",{},z.strictObject({status:z.literal("answered")}),new AbortController().signal);
  expect(result).toMatchObject({success:false,code:"OUTPUT_INVALID"});
  expect(calls).toEqual(["/api/auth/session","/api/ops/ai/ask"]);
});

it("clears access even when a 401 response has a malformed error body",async()=>{
  const dispatchEvent=vi.fn();vi.stubGlobal("window",{dispatchEvent});
  vi.stubGlobal("fetch",vi.fn(async(url:string)=>url==="/api/auth/session"?Response.json({authenticated:true,roles:["ROLE_OPERATOR"],username:"operator",csrfToken:"current"}):new Response("expired",{status:401})));
  const result=await copilotRequest("source",{},z.unknown(),new AbortController().signal);
  expect(result).toMatchObject({success:false,code:"SESSION_EXPIRED",status:401});
  expect(dispatchEvent).toHaveBeenCalledTimes(1);
});
