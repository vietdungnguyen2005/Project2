import { timingSafeEqual, randomUUID } from "node:crypto";
import { z } from "zod";
import type { Result } from "./spec";

export async function readBounded(response: Response | Request, limit: number, signal?: AbortSignal): Promise<Uint8Array<ArrayBuffer>> {
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array();
  const chunks: Uint8Array[] = []; let size = 0;
  const cancel = () => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener("abort", cancel, { once: true });
  try {
    while (true) {
      signal?.throwIfAborted();
      const next = await reader.read();
      signal?.throwIfAborted();
      if (next.done) break;
      size += next.value.length;
      if (size > limit) { cancel(); throw new Error("BODY_LIMIT"); }
      chunks.push(next.value);
    }
    const bytes = new Uint8Array(size); let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    return bytes;
  } finally { signal?.removeEventListener("abort", cancel); reader.releaseLock(); }
}

export function equal(a: string, b: string): boolean {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length > 0 && x.length === y.length && timingSafeEqual(x, y);
}

const Session = z.object({ authenticated: z.boolean(), username: z.string().max(200),
  roles: z.array(z.string().max(100)).max(20), csrfToken: z.string().min(1).max(4096) })
  .refine(s => !s.authenticated || s.username.trim().length > 0);

export class JavaSession {
  private cookie: string;
  readonly refreshedCookies: string[] = [];
  authorizationStatus: 401 | 403 | undefined;
  constructor(private request: Request, readonly signal: AbortSignal, readonly requestId = randomUUID()) {
    const cookies = (request.headers.get("cookie") ?? "").split(";").map(v => v.trim())
      .filter(v => v.startsWith("VMARKET_SESSION="));
    this.cookie = cookies.length === 1 && cookies[0].length <= 4096 ? cookies[0] : "";
  }
  async get(path: string): Promise<Response> {
    const origin = process.env.BACKEND_ORIGIN?.replace(/\/$/, "");
    if (!origin || !/^https?:\/\//.test(origin)) throw new Error("CONFIGURATION_UNAVAILABLE");
    const headers = new Headers({ Accept: "application/json" });
    headers.set("X-Request-Id",this.requestId);
    if (this.cookie) headers.set("Cookie", this.cookie);
    headers.set("X-V-Market-BFF-Secret", process.env.BFF_SHARED_SECRET ?? "");
    if (path.startsWith("/api/ops/")) headers.set("X-V-Market-Ops-Secret", process.env.VMARKET_OPS_SECRET ?? "");
    const response = await fetch(origin + path, { method: "GET", headers, signal: this.signal,
      cache: "no-store", redirect: "error" });
    if (response.status === 401 || response.status === 403) this.authorizationStatus = response.status;
    for (const item of response.headers.getSetCookie()) {
      const part = item.split(";")[0];
      if (part.startsWith("VMARKET_SESSION=") && part.length <= 4096) {
        this.cookie = part; this.refreshedCookies.push(item);
      }
    }
    return response;
  }
  async check(expected?: string, csrf = false): Promise<Result<string>> {
    try {
      const response = await this.get("/api/auth/session");
      if (!response.ok) void response.body?.cancel().catch(() => undefined);
      if (response.status === 401 || response.status === 403) return { success: false, code: "SESSION_EXPIRED", status: response.status };
      if (!response.ok) return { success: false, code: "DEPENDENCY_UNAVAILABLE", status: 503 };
      const data = Session.safeParse(JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(await readBounded(response, 16_384, this.signal))));
      if (!data.success) return { success: false, code: "OUTPUT_INVALID", status: 502 };
      if (!data.data.authenticated) return { success: false, code: "SESSION_EXPIRED", status: 401 };
      if (!data.data.roles.includes("ROLE_OPERATOR") || (expected && expected !== data.data.username)) return { success: false, code: "ACCESS_DENIED", status: 403 };
      if (csrf && !equal(this.request.headers.get("x-csrf-token") ?? "", data.data.csrfToken)) return { success: false, code: "ACCESS_DENIED", status: 403 };
      return { success: true, data: data.data.username };
    } catch { return { success: false, code: "DEPENDENCY_UNAVAILABLE", status: 503 }; }
  }
}
