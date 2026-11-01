import { createHmac, randomUUID } from "node:crypto";
import { BrowserInput, StepResponse, type AgentGatewaySpec, type GatewayFailure, type Result } from "./spec";
import { JavaSession, readBounded } from "./io";
import { signedRequest, type TransportContext } from "./transport";
import { executeRead, type Observation } from "./tools";
import { KnowledgeData } from "./knowledge";
import { actorRateLimit } from "./limits";

const SYNTHETIC = ["ed76d914-7c66-5102-9178-676334063938", "4aa83e9f-0a0a-5558-8f5e-0f87db27c923"];
const fail = (code: GatewayFailure, status: number): Result<never> => ({ success: false, code, status });
function transportFailure(response: Response): Result<never> {
  void response.body?.cancel().catch(() => undefined);
  const codes: Partial<Record<number,GatewayFailure>> = { 400:"INPUT_INVALID",401:"ACCESS_DENIED",403:"ACCESS_DENIED",
    409:"STEP_CONFLICT",413:"INPUT_INVALID",429:"BUDGET_EXCEEDED",502:"OUTPUT_INVALID",504:"DEADLINE_EXCEEDED" };
  return fail(codes[response.status] ?? "DEPENDENCY_UNAVAILABLE", codes[response.status] ? response.status : 503);
}

export class AgentGatewayHandler implements AgentGatewaySpec {
  readonly cookies: string[] = [];
  readonly requestId = randomUUID();
  private deadlineAt = 0;
  async execute(request: Request): Promise<Result<unknown>> {
    if (process.env.AI_COPILOT_ENABLED !== "true") return fail("AI_DISABLED", 503);
    this.deadlineAt = Date.now()+25_000;
    const controller = new AbortController();
    const signal = AbortSignal.any([request.signal, controller.signal]);
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      return await Promise.race([this.run(request, signal), new Promise<Result<never>>(resolve => {
        timer = setTimeout(() => { controller.abort(); resolve(fail("DEADLINE_EXCEEDED", 504)); }, 25_000);
      }), new Promise<Result<never>>(resolve => {
        if (request.signal.aborted) resolve(fail("DEADLINE_EXCEEDED", 499));
        else request.signal.addEventListener("abort", () => resolve(fail("DEADLINE_EXCEEDED", 499)), { once: true });
      })]);
    } catch (error) {
      if (signal.aborted || (error instanceof Error && error.message === "DEADLINE_EXCEEDED")) return fail("DEADLINE_EXCEEDED",504);
      return fail("DEPENDENCY_UNAVAILABLE",503);
    }
    finally { clearTimeout(timer); controller.abort(); }
  }

  private async run(request: Request, signal: AbortSignal): Promise<Result<unknown>> {
    const url = new URL(request.url);
    const host = request.headers.get("host");
    if (host) url.host = host;
    if (request.method !== "POST" || request.headers.get("origin") !== url.origin || request.headers.get("sec-fetch-site") === "cross-site") return fail("ACCESS_DENIED", 403);
    let input;
    try { input = BrowserInput.parse(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(await readBounded(request, 65_536, signal)))); }
    catch (error) { return fail("INPUT_INVALID", error instanceof Error && error.message === "BODY_LIMIT" ? 413 : 400); }
    const session = new JavaSession(request, signal, this.requestId);
    try {
      const authenticated = await session.check(undefined, true);
      if (!authenticated.success) return authenticated;
      const actor = authenticated.data;
      const actorKey = process.env.AI_ACTOR_HMAC_KEY ?? "";
      if (Buffer.byteLength(actorKey) < 32) return fail("CONFIGURATION_UNAVAILABLE", 503);
      const configured = process.env.AI_COLLECTION_IDS_JSON ? JSON.parse(process.env.AI_COLLECTION_IDS_JSON) as unknown : SYNTHETIC;
      if (!Array.isArray(configured) || configured.length > 10 || configured.some(s => !SYNTHETIC.includes(s))) return fail("CONFIGURATION_UNAVAILABLE", 503);
      const scopes = input.collection_ids ?? configured as string[];
      if (new Set(scopes).size !== scopes.length || scopes.some(s => !configured.includes(s))) return fail("ACCESS_DENIED", 403);
      const context: TransportContext = { actor_ref: createHmac("sha256", actorKey).update("vmarket-ai-actor:v1\n"+actor).digest("hex"),
        request_id: this.requestId, run_id: randomUUID(), step: 0, collection_ids: scopes, deadline_at: new Date(this.deadlineAt).toISOString() };
      if (!actorRateLimit.consume(context.actor_ref)) return fail("BUDGET_EXCEEDED",429);
      const history: Observation[] = [], callIds = new Set<string>();
      const origin = process.env.AI_SERVICE_ORIGIN?.replace(/\/$/, "");
      if (!origin || !/^https?:\/\//.test(origin)) return fail("CONFIGURATION_UNAVAILABLE", 503);
      for (let step = 0; step <= 3; step++) {
        signal.throwIfAborted(); context.step = step;
        const payload = { protocol_version: 1, request_id: context.request_id, run_id: context.run_id, step,
          question: input.question, collection_ids: scopes, tool_result: history.at(-1) ?? null, previous_results: history.slice(0,-1) };
        const signed = signedRequest(context, "/ai/agent/step", payload);
        const response = await fetch(origin+"/ai/agent/step", { method: "POST", ...signed, cache: "no-store", redirect: "error", signal });
        if (!response.ok) return transportFailure(response);
        const parsed = StepResponse.safeParse(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(await readBounded(response,65_536,signal))));
        if (!parsed.success) return fail("OUTPUT_INVALID", 502);
        const next = parsed.data;
        if (next.request_id !== context.request_id || next.run_id !== context.run_id || next.step !== step) return fail("STEP_CONFLICT",409);
        if (next.kind !== "tool_intent") {
          const checked = await session.check(actor, true);
          if (!checked.success) return checked;
          if (next.status === "answered" && (!history.length || history.some(h => h.status !== "ok"))) return fail("OUTPUT_INVALID",502);
          const message = next.kind === "handoff"
            ? "Use the existing authenticated operator workflow for this business action."
            : history.map(h => `${h.tool_name}: ${h.status}${h.truncated ? " (truncated)" : ""}`).join("; ") || "No supported read-only observation.";
          return { success: true, data: { result: { ...next, message }, observations: history } };
        }
        if (step === 3 || callIds.has(next.intent.call_id)) return fail("BUDGET_EXCEEDED",429);
        callIds.add(next.intent.call_id);
        const checked = await session.check(actor,true);
        if (!checked.success) return checked;
        if ("job_id" in next.intent.arguments && !(input.question+JSON.stringify(history)).includes(next.intent.arguments.job_id)) return fail("OUTPUT_INVALID",502);
        let observed: Observation;
        if (next.intent.tool_name === "search_supplier_knowledge") {
          if (next.intent.arguments.collection_ids.some(s => !scopes.includes(s))) return fail("ACCESS_DENIED",403);
          const search = signedRequest(context,"/ai/knowledge/search",next.intent.arguments);
          const response = await fetch(origin+"/ai/knowledge/search", { method: "POST", ...search, signal, cache: "no-store", redirect: "error" });
          if (!response.ok) return transportFailure(response);
          const data = KnowledgeData.safeParse(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(await readBounded(response,16_384,signal))));
          if (!data.success) return fail("OUTPUT_INVALID",502);
          observed = { call_id: next.intent.call_id, tool_name: next.intent.tool_name, observed_at: new Date().toISOString(),
            status: "ok", truncated: false, data: data.data, error_code: null };
          if (Buffer.byteLength(JSON.stringify(observed)) > 16_384) return fail("OUTPUT_INVALID",502);
        } else observed = await executeRead(next.intent,session);
        if (observed.status === "denied") return fail("ACCESS_DENIED",session.authorizationStatus ?? 403);
        history.push(observed);
        console.info(JSON.stringify({ event: "ai_tool_read", request_id: context.request_id, run_id: context.run_id,
          step, tool_name: observed.tool_name, status: observed.status }));
      }
      return fail("BUDGET_EXCEEDED",429);
    } finally { this.cookies.push(...session.refreshedCookies); }
  }
}

export async function handleAgent(request: Request): Promise<Response> {
  const gateway = new AgentGatewayHandler();
  const result = await gateway.execute(request);
  const headers = new Headers({ "Cache-Control": "no-store", "Content-Type": "application/json" });
  headers.set("X-Request-ID",gateway.requestId);
  for (const cookie of gateway.cookies) headers.append("Set-Cookie",cookie);
  return Response.json(result.success ? result.data : { code: result.code }, { status: result.success ? 200 : result.status, headers });
}
