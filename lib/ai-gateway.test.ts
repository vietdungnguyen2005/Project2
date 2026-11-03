import { afterEach, expect, it, vi } from "vitest";
import { handleAgent } from "./ai-gateway/handler";
import { BrowserInput, ToolIntent } from "./ai-gateway/spec";
import { projectTool } from "./ai-gateway/projection";
import { executeRead } from "./ai-gateway/tools";
import { JavaSession } from "./ai-gateway/io";
import { generateKeyPairSync, verify } from "node:crypto";
import { signedRequest } from "./ai-gateway/transport";
import { ActorRateLimit } from "./ai-gateway/limits";

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it("fails closed while feature is off without reading browser identity or calling upstream", async () => {
  vi.stubEnv("AI_COPILOT_ENABLED", "false");
  const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock);
  const response = await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", {
    method: "POST", headers: { "X-AI-Actor": "admin" }, body: "{}",
  }));
  expect(response.status).toBe(503);
  expect(await response.json()).toEqual({ code: "AI_DISABLED" });
  expect(fetchMock).not.toHaveBeenCalled();
});

it("parses only browser question/scopes and closed read-only tool arguments", () => {
  expect(BrowserInput.safeParse({ question: "Inspect imports", actor_ref: "admin" }).success).toBe(false);
  expect(BrowserInput.safeParse({ question: "Inspect imports" }).success).toBe(true);
  expect(ToolIntent.safeParse({ call_id: crypto.randomUUID(), tool_name: "confirm_import", arguments: {} }).success).toBe(false);
  expect(ToolIntent.safeParse({ call_id: crypto.randomUUID(), tool_name: "inspect_import_job", arguments: { job_id: "../restart" } }).success).toBe(false);
  expect(ToolIntent.safeParse({ call_id: crypto.randomUUID(), tool_name: "get_imports", arguments: {} }).success).toBe(true);
});

it("rejects a guest from current Java session and never forwards forged credentials", async () => {
  vi.stubEnv("AI_COPILOT_ENABLED", "true");
  vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
  vi.stubEnv("BFF_SHARED_SECRET", "server-bff");
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ authenticated: false, username: "", roles: [], csrfToken: "current" }));
  vi.stubGlobal("fetch", fetchMock);
  const response = await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", {
    method: "POST", headers: { Origin: "http://localhost:3000", "Content-Type": "application/json",
      "X-V-Market-BFF-Secret": "forged", "X-CSRF-TOKEN": "current" }, body: JSON.stringify({ question: "List imports" }),
  }));
  expect(response.status).toBe(401);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(new Headers(fetchMock.mock.calls[0][1].headers).get("X-V-Market-BFF-Secret")).toBe("server-bff");
});

it("projects profiles without audit identity and refuses incompatible Java target policy", () => {
  const profile = { profileId: "supplier-b", version: 1, vendorCode: "RIVERBYTE", encoding: "UTF-8",
    mapping: { sku: "SKU", name: "ProductName", on_hand: "AvailableQuantity" }, skuNormalization: "TRIM_UPPER",
    active: true, requiredFields: ["sku", "name", "on_hand"],
    normalization: { sku: "TRIM_UPPER", name: "TRIM", on_hand: "TRIM_INTEGER" },
    validation: { sku: { type: "string", minLength: 1, maxLength: 64 }, name: { type: "string", minLength: 1, maxLength: 160 }, on_hand: { type: "integer", min: 0, max: 1000000 } },
    createdBy: "private-user", updatedBy: "private-user" };
  const projected = projectTool("get_integration_profiles", [profile]);
  expect(projected.success).toBe(true);
  expect(JSON.stringify(projected)).not.toContain("private-user");
  expect(projectTool("get_integration_profiles", [{ ...profile, requiredFields: ["price"] }]).success).toBe(false);
});

const rawJob = { id: "11111111-1111-4111-8111-111111111111", status: "FAILED", totalRows: 1,
  validRows: 0, appliedRows: 0, rejectedRows: 1, checkpointRow: 0, importMode: "VERSIONED_STOCK",
  profileId: "supplier-b", profileVersion: 1, sourceName: "private.csv" };

it("bounds inspection errors and replaces unknown row text with a code", () => {
  const result = projectTool("inspect_import_job", { job: rawJob, workState: "FAILED", recoveryCount: 1,
    restartAllowed: true, lastRecoveryReason: "private recovery", errors: Array.from({ length: 101 }, () => ({ rowNumber: 2, reason: "private source cell and token" })) });
  expect(result.success).toBe(true);
  expect(JSON.stringify(result)).not.toContain("private");
  expect(JSON.stringify(result)).toContain('"code":"UNKNOWN_ROW_ERROR"');
  if (result.success) expect(result.data.truncated).toBe(true);
});

it("projects stored reconciliation reports without values/notes and validates discrepancy types", () => {
  const raw = { id: crypto.randomUUID(), migrationJobId: rawJob.id, status: "COMPLETED", mismatchCount: 1,
    changedSinceImportRows: 0, stockNotVerifiedRows: 0, createdAt: "2026-11-01T00:00:00Z",
    discrepancies: [{ sku: "VM-002", type: "QUANTITY_MISMATCH", expectedValue: "private", actualValue: "private", resolutionNote: "private" }] };
  const result = projectTool("get_reconciliation_report", raw);
  expect(result.success).toBe(true);
  expect(JSON.stringify(result)).not.toContain("private");
  expect(projectTool("get_reconciliation_report", { ...raw, discrepancies: [{ sku: "VM-002", type: "RUN_SQL" }] }).success).toBe(false);
});

it("executes only fixed Java GETs and reports a missing job without retrying", async () => {
  vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
  const fetchMock = vi.fn().mockResolvedValue(new Response("private upstream failure", { status: 400 }));
  vi.stubGlobal("fetch", fetchMock);
  const intent = ToolIntent.parse({ call_id: crypto.randomUUID(), tool_name: "inspect_import_job", arguments: { job_id: rawJob.id } });
  const result = await executeRead(intent, new JavaSession(new Request("http://localhost"), new AbortController().signal));
  expect(result.status).toBe("unavailable");
  expect(JSON.stringify(result)).not.toContain("private");
  expect(fetchMock).toHaveBeenCalledTimes(1);
  expect(fetchMock.mock.calls[0][0]).toBe(`http://127.0.0.1:8080/api/ops/imports/${rawJob.id}/inspection`);
  expect(fetchMock.mock.calls[0][1].method).toBe("GET");
});

it("signs exact body/route and pseudonymous context without forwarding Java credentials", () => {
  const keys = generateKeyPairSync("ed25519");
  vi.stubEnv("AI_SIGNING_KEY_PKCS8_BASE64", keys.privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"));
  vi.stubEnv("AI_SIGNING_KID", "test");
  const signed = signedRequest({ actor_ref: "a".repeat(64), request_id: crypto.randomUUID(), run_id: crypto.randomUUID(),
    step: 0, collection_ids: [], deadline_at: new Date(Date.now()+20000).toISOString() }, "/ai/agent/step", { question: "List imports" });
  expect(verify(null, Buffer.from("vmarket-ai-context:v1."+signed.headers.get("X-AI-Context")), keys.publicKey,
    Buffer.from(signed.headers.get("X-AI-Signature")!, "base64url"))).toBe(true);
  expect(signed.headers.has("Cookie")).toBe(false);
  expect(signed.headers.has("X-V-Market-BFF-Secret")).toBe(false);
  const context = JSON.parse(Buffer.from(signed.headers.get("X-AI-Context")!, "base64url").toString());
  expect(context.actor_ref).toBe("a".repeat(64));
  expect(context.operation).toBe("agent_step");
});

it("runs an authenticated bounded two-step read with per-tool and final session checks", async () => {
  const key = generateKeyPairSync("ed25519");
  vi.stubEnv("AI_COPILOT_ENABLED", "true"); vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
  vi.stubEnv("AI_SERVICE_ORIGIN", "http://127.0.0.1:8092"); vi.stubEnv("AI_ACTOR_HMAC_KEY", "a".repeat(64));
  vi.stubEnv("AI_SIGNING_KID", "test"); vi.stubEnv("AI_SIGNING_KEY_PKCS8_BASE64", key.privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"));
  const seen: { url: string; init: RequestInit }[] = [];
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    seen.push({ url, init });
    if (url.endsWith("/api/auth/session")) return Response.json({ authenticated: true, username: "private-operator", roles: ["ROLE_OPERATOR"], csrfToken: "current" });
    if (url.endsWith("/api/ops/imports")) return Response.json([rawJob]);
    const headers = new Headers(init.headers);
    expect(headers.has("Cookie")).toBe(false); expect(headers.has("X-CSRF-TOKEN")).toBe(false);
    expect(headers.has("X-V-Market-BFF-Secret")).toBe(false);
    const body = JSON.parse(init.body as string);
    if (body.step === 0) return Response.json({ protocol_version: 1, request_id: body.request_id, run_id: body.run_id,
      step: 0, kind: "tool_intent", intent: { call_id: crypto.randomUUID(), tool_name: "get_imports", arguments: {} } });
    expect(body.tool_result.data.imports[0].job_id).toBe(rawJob.id);
    return Response.json({ protocol_version: 1, request_id: body.request_id, run_id: body.run_id, step: 1,
      kind: "final", message: "One observed import job", citations: [], status: "answered", handoff_reason: null });
  });
  vi.stubGlobal("fetch", fetchMock);
  const response = await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", { method: "POST",
    headers: { Origin: "http://localhost:3000", "Content-Type": "application/json", "X-CSRF-TOKEN": "current", Cookie: "VMARKET_SESSION=browser-session" },
    body: JSON.stringify({ question: "List imports" }) }));
  expect(response.status).toBe(200);
  expect(seen.filter(s => s.url.endsWith("/api/auth/session"))).toHaveLength(3);
  expect(JSON.stringify(await response.json())).not.toContain("private-operator");
});

it("executes knowledge search only through a separately signed narrowed AI request", async () => {
  const key = generateKeyPairSync("ed25519");
  vi.stubEnv("AI_COPILOT_ENABLED", "true"); vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
  vi.stubEnv("AI_SERVICE_ORIGIN", "http://127.0.0.1:8092"); vi.stubEnv("AI_ACTOR_HMAC_KEY", "k".repeat(64));
  vi.stubEnv("AI_SIGNING_KID", "test"); vi.stubEnv("AI_SIGNING_KEY_PKCS8_BASE64", key.privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"));
  const collection = "ed76d914-7c66-5102-9178-676334063938";
  let searches = 0;
  vi.stubGlobal("fetch", vi.fn(async (url: string, init: RequestInit) => {
    if (url.endsWith("/api/auth/session")) return Response.json({ authenticated: true, username: "knowledge-operator", roles: ["ROLE_OPERATOR"], csrfToken: "current" });
    const body = JSON.parse(init.body as string);
    if (url.endsWith("/ai/knowledge/search")) {
      searches++;
      const claims = JSON.parse(Buffer.from(new Headers(init.headers).get("X-AI-Context")!, "base64url").toString());
      expect(claims.operation).toBe("knowledge_search"); expect(body.collection_ids).toEqual([collection]);
      return Response.json({ hits: [] });
    }
    if (body.step === 0) return Response.json({ protocol_version: 1, request_id: body.request_id, run_id: body.run_id, step: 0,
      kind: "tool_intent", intent: { call_id: crypto.randomUUID(), tool_name: "search_supplier_knowledge", arguments: { query: "encoding", collection_ids: [collection], top_k: 1 } } });
    return Response.json({ protocol_version: 1, request_id: body.request_id, run_id: body.run_id, step: 1, kind: "final",
      message: "No knowledge hits", status: "abstained", citations: [], handoff_reason: null });
  }));
  const response = await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", { method: "POST",
    headers: { Origin: "http://localhost:3000", "X-CSRF-TOKEN": "current" },
    body: JSON.stringify({ question: "Search supplier knowledge", collection_ids: [collection] }) }));
  expect(response.status).toBe(200); expect(searches).toBe(1);
});

it("bounds per-actor requests and rate-limit memory without caching tool results", () => {
  const limit = new ActorRateLimit(2, 1000, 2);
  expect(limit.consume("actor-a", 0)).toBe(true);
  expect(limit.consume("actor-a", 1)).toBe(true);
  expect(limit.consume("actor-a", 2)).toBe(false);
  expect(limit.consume("actor-b", 2)).toBe(true);
  expect(limit.consume("actor-c", 2)).toBe(false);
  expect(limit.consume("actor-a", 1001)).toBe(true);
});

it.each([{ authenticated: false, roles: [], expected: 401 },
  { authenticated: true, roles: ["ROLE_GUEST"], expected: 403 },
  { authenticated: true, roles: ["ROLE_OPERATOR"], expected: 403 },
])("rejects expired/revoked/role-denied or stale CSRF before AI work: $expected", async ({ authenticated, roles, expected }) => {
  vi.stubEnv("AI_COPILOT_ENABLED", "true"); vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
  const fetchMock = vi.fn().mockResolvedValue(Response.json({ authenticated, roles, username: "operator", csrfToken: "current" }));
  vi.stubGlobal("fetch",fetchMock);
  const response = await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", { method: "POST",
    headers: { Origin: "http://localhost:3000", "X-CSRF-TOKEN": "stale" }, body: JSON.stringify({ question: "List imports" }) }));
  expect(response.status).toBe(expected); expect(fetchMock).toHaveBeenCalledTimes(1);
});

it("aborts a hanging upstream at the outer deadline without continuing a privileged loop", async () => {
  vi.useFakeTimers();
  try {
    vi.stubEnv("AI_COPILOT_ENABLED", "true"); vi.stubEnv("BACKEND_ORIGIN", "http://127.0.0.1:8080");
    let upstreamSignal: AbortSignal | undefined;
    vi.stubGlobal("fetch",vi.fn((_url,init) => { upstreamSignal=init.signal; return new Promise(() => {}); }));
    const pending=handleAgent(new Request("http://localhost:3000/api/ops/ai/agent", { method: "POST",
      headers: { Origin: "http://localhost:3000" }, body: JSON.stringify({ question: "List imports" }) }));
    await vi.advanceTimersByTimeAsync(25_001);
    const response=await pending;
    expect(response.status).toBe(504); expect(upstreamSignal?.aborted).toBe(true);
  } finally { vi.useRealTimers(); }
});

it("generates its own request trace instead of trusting a caller trace or actor", async () => {
  vi.stubEnv("AI_COPILOT_ENABLED","false");
  const supplied=crypto.randomUUID();
  const response=await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent",{
    method:"POST",headers:{"X-Request-ID":supplied,"X-AI-Actor":"admin"},body:"{}"}));
  expect(response.headers.get("X-Request-ID")).toMatch(/^[0-9a-f-]{36}$/);
  expect(response.headers.get("X-Request-ID")).not.toBe(supplied);
});

it("refuses an authenticated upstream session without a stable account identity", async () => {
  vi.stubEnv("AI_COPILOT_ENABLED","true"); vi.stubEnv("BACKEND_ORIGIN","http://127.0.0.1:8080");
  vi.stubGlobal("fetch",vi.fn().mockResolvedValue(Response.json({authenticated:true,username:"",roles:["ROLE_OPERATOR"],csrfToken:"current"})));
  const response=await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent",{method:"POST",
    headers:{Origin:"http://localhost:3000","X-CSRF-TOKEN":"current"},body:JSON.stringify({question:"List imports"})}));
  expect(response.status).toBe(502);
});

async function scenario(reply: (body: { request_id: string; run_id: string; step: number },init:RequestInit) => unknown, authClockShift=0) {
  const key=generateKeyPairSync("ed25519");
  vi.stubEnv("AI_COPILOT_ENABLED","true"); vi.stubEnv("BACKEND_ORIGIN","http://127.0.0.1:8080");
  vi.stubEnv("AI_SERVICE_ORIGIN","http://127.0.0.1:8092"); vi.stubEnv("AI_ACTOR_HMAC_KEY",crypto.randomUUID()+crypto.randomUUID());
  vi.stubEnv("AI_SIGNING_KID","test"); vi.stubEnv("AI_SIGNING_KEY_PKCS8_BASE64",key.privateKey.export({format:"der",type:"pkcs8"}).toString("base64"));
  const fetchMock=vi.fn(async (url:string,init:RequestInit) => {
    if(url.endsWith("/api/auth/session")) {
      if(authClockShift) { vi.setSystemTime(Date.now()+authClockShift); authClockShift=0; }
      return Response.json({authenticated:true,username:"scenario-operator",roles:["ROLE_OPERATOR"],csrfToken:"current"});
    }
    if(url.endsWith("/api/ops/imports")) return Response.json([rawJob]);
    const result=reply(JSON.parse(init.body as string),init);
    return result instanceof Response ? result : Response.json(result);
  });
  vi.stubGlobal("fetch",fetchMock);
  const response=await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent",{method:"POST",
    headers:{Origin:"http://localhost:3000","X-CSRF-TOKEN":"current"},body:JSON.stringify({question:"List imports"})}));
  return {response,fetchMock};
}
it("uses Workers-supported manual redirects for agent requests",async()=>{
  const {response}=await scenario((body,init)=>{
    if(init.redirect!=="manual")throw new TypeError("Workers requires manual redirect handling");
    return {protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,kind:"final",
      message:"No supported observation",citations:[],status:"abstained",handoff_reason:null};
  });
  expect(response.status).toBe(200);
});

it("refuses a claimed answered result with no tool observations",async()=>{
  const {response}=await scenario(body=>({protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,
    kind:"final",message:"Imports completed",citations:[],status:"answered",handoff_reason:null}));
  expect(response.status).toBe(502);
});

it("does not repeat Python's unsupported business-success narrative after a successful read",async()=>{
  const {response}=await scenario(body=>body.step===0 ? {protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:0,
    kind:"tool_intent",intent:{call_id:crypto.randomUUID(),tool_name:"get_imports",arguments:{}}} :
    {protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:1,kind:"final",message:"Inventory updated successfully",
      citations:[],status:"answered",handoff_reason:null});
  expect(response.status).toBe(200);
  expect(JSON.stringify(await response.json())).not.toContain("Inventory updated");
});

it("checks the connection Host for Next's internal localhost URL and ignores forwarded-host spoofing",async()=>{
  vi.stubEnv("AI_COPILOT_ENABLED","true"); vi.stubEnv("BACKEND_ORIGIN","http://127.0.0.1:8080");
  vi.stubGlobal("fetch",vi.fn().mockResolvedValue(Response.json({authenticated:false,username:"",roles:[],csrfToken:"x"})));
  const response=await handleAgent(new Request("http://localhost:13009/api/ops/ai/agent",{method:"POST",
    headers:{Host:"127.0.0.1:13009",Origin:"http://127.0.0.1:13009","X-Forwarded-Host":"evil.example"},body:JSON.stringify({question:"List imports"})}));
  expect(response.status).toBe(401);
});

it.each([401,403,409,413,429,502,504])("preserves a safe AI transport failure status %s without raw error text",async status=>{
  const {response}=await scenario(()=>new Response("private upstream error",{status}));
  expect(response.status).toBe(status);
  expect(JSON.stringify(await response.json())).not.toContain("private");
});

it("rejects a fourth tool and never issues a Java write",async()=>{
  const {response,fetchMock}=await scenario(body=>({protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,
    kind:"tool_intent",intent:{call_id:crypto.randomUUID(),tool_name:"get_imports",arguments:{}}}));
  expect(response.status).toBe(429);
  const businessCalls=fetchMock.mock.calls.filter(([url])=>url.includes("/api/ops/"));
  expect(businessCalls).toHaveLength(3);
  expect(businessCalls.every(([,init])=>init.method==="GET")).toBe(true);
});

it("revalidates session between Python intent and Java tool execution",async()=>{
  const revoked=vi.fn().mockResolvedValue(Response.json({authenticated:false,username:"",roles:[],csrfToken:"new"}));
  const {response}=await scenario(body=>{
    vi.stubGlobal("fetch",revoked);
    return {protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,kind:"tool_intent",
      intent:{call_id:crypto.randomUUID(),tool_name:"get_imports",arguments:{}}};
  });
  expect(response.status).toBe(401);
  expect(revoked).toHaveBeenCalledTimes(1);
  expect(revoked.mock.calls[0][0]).toBe("http://127.0.0.1:8080/api/auth/session");
});

it.each(["confirm_import","http://evil.example","run_sql"])("rejects adversarial tool name %s before Java",async tool_name=>{
  const {response,fetchMock}=await scenario(body=>({protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,
    kind:"tool_intent",intent:{call_id:crypto.randomUUID(),tool_name,arguments:{url:"http://evil.example"}}}));
  expect(response.status).toBe(502);
  expect(fetchMock.mock.calls.some(([url])=>url.includes("/api/ops/"))).toBe(false);
});

it("reads the actual Java RowError.reason contract and never passes its text through",()=>{
  const result=projectTool("inspect_import_job",{job:rawJob,workState:"FAILED",recoveryCount:0,restartAllowed:true,
    errors:[{rowNumber:2,reason:"PRIVATE_ROW_SENTINEL"}]});
  expect(result.success).toBe(true);
  expect(JSON.stringify(result)).not.toContain("PRIVATE_ROW_SENTINEL");
});

it("maps source-verified row-error prefixes to codes while discarding their details",()=>{
  const result=projectTool("inspect_import_job",{job:rawJob,workState:"FAILED",recoveryCount:0,restartAllowed:true,
    errors:[{rowNumber:2,reason:"STALE_STOCK_VERSION: PRIVATE_SOURCE_SENTINEL"}]});
  expect(result.success).toBe(true);
  expect(JSON.stringify(result)).toContain('"code":"STALE_STOCK_VERSION"');
  expect(JSON.stringify(result)).not.toContain("PRIVATE_SOURCE_SENTINEL");
});

it("does not extend the signed deadline by time already spent authenticating",async()=>{
  vi.useFakeTimers();
  try {
    const started=Date.now(); let deadline=0;
    const {response}=await scenario((body,init)=>{
      deadline=Date.parse(JSON.parse(Buffer.from(new Headers(init.headers).get("X-AI-Context")!,"base64url").toString()).deadline_at);
      return {protocol_version:1,request_id:body.request_id,run_id:body.run_id,step:body.step,kind:"final",
        message:"No observation",status:"abstained",citations:[],handoff_reason:null};
    },5000);
    expect(response.status).toBe(200); expect(deadline).toBeLessThanOrEqual(started+25000);
  } finally {vi.useRealTimers();}
});

it("rejects a browser body above the byte cap before authentication or AI calls",async()=>{
  vi.stubEnv("AI_COPILOT_ENABLED","true"); const fetchMock=vi.fn(); vi.stubGlobal("fetch",fetchMock);
  const response=await handleAgent(new Request("http://localhost:3000/api/ops/ai/agent",{method:"POST",
    headers:{Origin:"http://localhost:3000"},body:"x".repeat(65537)}));
  expect(response.status).toBe(413); expect(fetchMock).not.toHaveBeenCalled();
});
