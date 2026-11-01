import { afterEach,expect,it,vi } from "vitest";
import { ProfileDraftClient } from "./ai-mapping/client";
afterEach(()=>vi.unstubAllGlobals());
it("creates only an inactive version on an explicit call and never activates or imports it",async()=>{
  const definition={profileId:"operator-draft",version:1,encoding:"UTF-8" as const,vendorCode:"RIVERBYTE",mapping:{sku:"SKU",name:"ProductName",on_hand:"AvailableQuantity"},skuNormalization:"TRIM" as const};
  const fetch=vi.fn(async(url:string)=>url==="/api/auth/session"?Response.json({authenticated:true,csrfToken:"current"}):Response.json({...definition,active:false}));
  vi.stubGlobal("fetch",fetch);
  const result=await new ProfileDraftClient().publish(definition);
  expect(result.success).toBe(true);expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls[1][0]).toBe("/api/ops/integration-profiles");
});

it("activates only a saved profile on a separate call with current CSRF",async()=>{
  const saved={profileId:"operator-draft",version:2,encoding:"UTF-8" as const,vendorCode:"RIVERBYTE",mapping:{sku:"SKU",name:"ProductName",on_hand:"AvailableQuantity"},skuNormalization:"TRIM" as const,active:false};
  const calls:{url:string;init?:RequestInit}[]=[];
  vi.stubGlobal("fetch",async(url:string,init?:RequestInit)=>{calls.push({url,init});return url==="/api/auth/session"?Response.json({authenticated:true,csrfToken:"fresh"}):Response.json({...saved,active:true});});
  expect((await new ProfileDraftClient().activate(saved)).success).toBe(true);
  expect(calls[1].url).toBe("/api/ops/integration-profiles/operator-draft/2");expect(calls[1].init?.method).toBe("PATCH");
  expect(JSON.parse(calls[1].init?.body as string)).toEqual({active:true});
  expect(new Headers(calls[1].init?.headers).get("X-CSRF-TOKEN")).toBe("fresh");
});
