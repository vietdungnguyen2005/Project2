import { NextResponse } from "next/server";

type BackendCredentials = {
  bffSecret?: string;
  opsSecret?: string;
};

const forwardedRequestHeaders = [
  "accept",
  "content-type",
  "idempotency-key",
  "x-source-name",
  "x-source-encoding",
] as const;

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
  const response = await fetch(`${origin}${path}`, {
    method,
    headers,
    body: method === "GET" || method === "HEAD" ? undefined : await request.arrayBuffer(),
    cache: "no-store",
  });
  const responseHeaders = new Headers({ "Cache-Control": "no-store" });
  const contentType = response.headers.get("content-type");
  const requestId = response.headers.get("x-request-id");
  if (contentType) responseHeaders.set("Content-Type", contentType);
  if (requestId) responseHeaders.set("X-Request-Id", requestId);

  return new Response(response.body, {
    status: response.status,
    headers: responseHeaders,
  });
}
