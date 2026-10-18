import { NextResponse } from "next/server";

type BackendCredentials = {
  bffSecret?: string;
  opsSecret?: string;
};

const forwardedRequestHeaders = [
  "accept",
  "content-type",
  "idempotency-key",
  "x-checkout-session",
  "x-source-name",
  "x-source-encoding",
  "cookie",
  "x-csrf-token",
  "prefer",
] as const;

class BodyBudgetExceeded extends Error {}
class ProxyDeadlineExceeded extends Error {}
class InvalidBackendResponse extends Error {}

async function withinDeadline<T>(milliseconds: number, task: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout>;
  const expired = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      const failure = new ProxyDeadlineExceeded();
      controller.abort(failure);
      reject(failure);
    }, milliseconds);
  });
  try { return await Promise.race([task(controller.signal), expired]); }
  finally { clearTimeout(timer!); }
}

async function readBoundedBody(request: Request, limit: number, signal: AbortSignal): Promise<Uint8Array<ArrayBuffer> | undefined> {
  const declared = request.headers.get("content-length");
  if (declared && /^\d+$/.test(declared) && Number(declared) > limit) {
    // Cancel without waiting for an untrusted producer's cancellation hook.
    void request.body?.cancel().catch(() => undefined);
    throw new BodyBudgetExceeded();
  }
  return readBoundedStream(request.body, limit, signal);
}

async function readBoundedStream(stream: ReadableStream<Uint8Array> | null, limit: number,
  signal?: AbortSignal): Promise<Uint8Array<ArrayBuffer> | undefined> {
  if (!stream) return undefined;
  const reader = stream.getReader();
  const cancel = () => { void reader.cancel().catch(() => undefined); };
  signal?.addEventListener("abort", cancel, { once: true });
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      signal?.throwIfAborted();
      const part = await reader.read();
      signal?.throwIfAborted();
      if (part.done) break;
      length += part.value.byteLength;
      if (length > limit) {
        void reader.cancel().catch(() => undefined);
        throw new BodyBudgetExceeded();
      }
      chunks.push(part.value);
    }
  } finally {
    signal?.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
  const body = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

export function buildBackendHeaders(
  incoming: Headers,
  credentials: BackendCredentials,
): Headers {
  const headers = new Headers();
  for (const name of forwardedRequestHeaders) {
    const value = incoming.get(name);
    if (value) headers.set(name, value);
  }
  if (credentials.bffSecret) {
    headers.set("X-V-Market-BFF-Secret", credentials.bffSecret);
  }
  if (credentials.opsSecret) {
    headers.set("X-V-Market-Ops-Secret", credentials.opsSecret);
  }
  return headers;
}

export async function proxyToBackend(
  request: Request,
  path: string,
  options: { operations?: boolean } = {},
): Promise<Response> {
  if (!["GET", "HEAD", "OPTIONS"].includes(request.method.toUpperCase())) {
    const origin = request.headers.get("origin");
    const publicUrl = new URL(request.url);
    // Host is supplied by the browser connection; do not trust client X-Forwarded-Host.
    const host = request.headers.get("host");
    if (host) publicUrl.host = host;
    if ((origin && origin !== publicUrl.origin) || request.headers.get("sec-fetch-site") === "cross-site") {
      return NextResponse.json({ code: "ORIGIN_REJECTED", message: "Cross-origin mutations are not allowed." }, { status: 403 });
    }
  }
  const origin = process.env.BACKEND_ORIGIN?.replace(/\/$/, "");
  if (!origin) {
    return NextResponse.json(
      {
        code: "BACKEND_UNAVAILABLE",
        message: "The Java backend has not been connected to this deployment.",
      },
      { status: 503, headers: { "Cache-Control": "no-store" } },
    );
  }

  const headers = buildBackendHeaders(request.headers, {
    bffSecret: process.env.BFF_SHARED_SECRET,
    opsSecret: options.operations ? process.env.VMARKET_OPS_SECRET : undefined,
  });
  const method = request.method.toUpperCase();
  let body: Uint8Array<ArrayBuffer> | undefined;
  try {
    body = method === "GET" || method === "HEAD" ? undefined
      : await withinDeadline(10_000, signal => readBoundedBody(request, path === "/api/ops/imports" ? 5_000_000 : 65_536, signal));
  } catch (failure) {
    const large = failure instanceof BodyBudgetExceeded;
    const timeout = failure instanceof ProxyDeadlineExceeded;
    return NextResponse.json({ code: large ? "REQUEST_TOO_LARGE" : timeout ? "REQUEST_BODY_TIMEOUT" : "REQUEST_BODY_UNREADABLE",
      message: large ? "Request body exceeds the allowed byte limit." : "The request body could not be read. Keep the original operation key when checking or retrying an unresolved checkout." },
      { status: large ? 413 : timeout ? 408 : 400, headers: { "Cache-Control": "no-store" } });
  }
  let response: Response;
  let responseBody: Uint8Array<ArrayBuffer> | undefined;
  try {
    const result = await withinDeadline(12_000, async signal => {
      const response = await fetch(`${origin}${path}`, { method, headers, body, cache: "no-store", signal });
      if (response.status >= 500) {
        void response.body?.cancel().catch(() => undefined);
        throw new Error("Backend server error");
      }
      const bytes = await readBoundedStream(response.body, 5_000_000, signal);
      const mediaType = response.headers.get("content-type")?.split(";")[0].trim().toLowerCase();
      if (method !== "HEAD" && response.status !== 204 && response.status !== 304
        && mediaType && /^application\/(?:[\w.-]+\+)?json$/.test(mediaType)) {
        try { JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
        catch { throw new InvalidBackendResponse(); }
      }
      return { response, bytes };
    });
    response = result.response;
    responseBody = result.bytes;
  } catch (failure) {
    if (failure instanceof InvalidBackendResponse) return NextResponse.json({ code: "BACKEND_INVALID_RESPONSE",
      message: "The backend response could not be verified. Mutation outcomes may be unknown; keep the original operation key and check its result." },
      { status: 502, headers: { "Cache-Control": "no-store" } });
    if (failure instanceof BodyBudgetExceeded) return NextResponse.json({ code: "BACKEND_RESPONSE_TOO_LARGE",
      message: "The backend response exceeded its byte budget. Mutation outcomes may be unknown; keep the original operation key and check its result." },
      { status: 502, headers: { "Cache-Control": "no-store" } });
    const timeout = failure instanceof ProxyDeadlineExceeded;
    return NextResponse.json({ code: timeout ? "BACKEND_TIMEOUT" : "BACKEND_UNAVAILABLE",
      message: "The backend response is unavailable. Mutation outcomes may be unknown; keep the original operation key and check its result before retrying." },
      { status: timeout ? 504 : 503, headers: { "Cache-Control": "no-store" } });
  }
  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  const contentType = response.headers.get("content-type");
  const requestId = response.headers.get("x-request-id");
  const preferenceApplied=response.headers.get("preference-applied");
  if (preferenceApplied) responseHeaders.set("Preference-Applied",preferenceApplied);
  if (contentType) responseHeaders.set("Content-Type", contentType);
  if (requestId) responseHeaders.set("X-Request-Id", requestId);
  for (const cookie of response.headers.getSetCookie()) {
    responseHeaders.append("Set-Cookie", cookie);
  }

  return new Response(responseBody, {
    status: response.status,
    headers: responseHeaders,
  });
}
