import { afterEach, describe, expect, it, vi } from "vitest";
import { buildBackendHeaders, proxyToBackend } from "@/lib/backend-proxy";

describe("backend BFF boundary", () => {
  it("sanitizes an unreadable request stream before dispatch", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    const body = new ReadableStream<Uint8Array>({ start(controller) { controller.error(new Error("private producer failure")); } });
    const response = await proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body, duplex: "half" } as RequestInit), "/api/orders");
    expect(response.status).toBe(400);
    expect((await response.json()).code).toBe("REQUEST_BODY_UNREADABLE");
    expect(upstream).not.toHaveBeenCalled();
  });

  it.each([400, 401, 403, 404, 409])("preserves verified JSON business responses with status %s", async status => {
    vi.useFakeTimers();
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const payload = { code: "EXPECTED_BUSINESS_RESULT", message: "Review the operation." };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(payload), {
      status, headers: { "Content-Type": "application/json", "X-Request-Id": "backend-request-1" },
    })));
    const response = await proxyToBackend(new Request("https://ui.test/api/orders/checkout"), "/api/orders/checkout");
    expect(response.status).toBe(status);
    expect(await response.json()).toEqual(payload);
    expect(response.headers.get("X-Request-Id")).toBe("backend-request-1");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("rejects a declared JSON success whose body is malformed rather than forwarding false success", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response('{"orderNumber":', {
      status: 201, headers: { "Content-Type": "application/json" },
    })));
    const response = await proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body: "{}" }), "/api/orders");
    expect(response.status).toBe(502);
    expect((await response.json()).code).toBe("BACKEND_INVALID_RESPONSE");
  });

  it("does not expose internal upstream server-error bodies or cookies", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("private SQL / credentials / stack", {
      status: 500, headers: { "Content-Type": "text/html", "Set-Cookie": "debug=internal" },
    })));
    const response = await proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body: "{}" }), "/api/orders");
    expect(response.status).toBe(503);
    expect((await response.json()).code).toBe("BACKEND_UNAVAILABLE");
    expect(response.headers.get("Set-Cookie")).toBeNull();
  });

  it("cancels a stalled upload without dispatching backend work", async () => {
    vi.useFakeTimers();
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    const cancel = vi.fn();
    const request = new Request("https://ui.test/api/orders", {
      method: "POST", body: new ReadableStream({ cancel }), duplex: "half",
    } as RequestInit);
    const pending = proxyToBackend(request, "/api/orders");
    await vi.advanceTimersByTimeAsync(10_001);
    expect(cancel).toHaveBeenCalledTimes(1);
    const response = await pending;
    expect(response.status).toBe(408);
    expect((await response.json()).code).toBe("REQUEST_BODY_TIMEOUT");
    expect(upstream).not.toHaveBeenCalled();
    expect(vi.getTimerCount()).toBe(0);
  });

  it("bounds buffered upstream bytes before returning headers or a partial successful response", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(new Uint8Array(5_000_001), { status: 200 })));
    const response = await proxyToBackend(new Request("https://ui.test/api/catalog/products"), "/api/catalog/products");
    expect(response.status).toBe(502);
    expect((await response.json()).code).toBe("BACKEND_RESPONSE_TOO_LARGE");
  });

  it("keeps the upstream deadline active until the response body finishes", async () => {
    vi.useFakeTimers();
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const cancel = vi.fn();
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(new ReadableStream({ cancel }), {
      status: 201, headers: { "Content-Type": "application/json" },
    })));
    const pending = proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body: "{}" }), "/api/orders");
    await vi.advanceTimersByTimeAsync(12_001);
    const response = await pending;
    expect(response.status).toBe(504);
    expect((await response.json()).code).toBe("BACKEND_TIMEOUT");
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("aborts a stalled upstream within its deadline without claiming a mutation failed", async () => {
    vi.useFakeTimers();
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn((_url: string, init: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init.signal?.addEventListener("abort", () => reject(init.signal?.reason), { once: true });
    }));
    vi.stubGlobal("fetch", upstream);
    const pending = proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body: "{}" }), "/api/orders");
    await vi.advanceTimersByTimeAsync(12_001);
    expect(upstream).toHaveBeenCalledTimes(1);
    expect(upstream.mock.calls[0][1].signal?.aborted).toBe(true);
    const response = await pending;
    expect(response.status).toBe(504);
    expect((await response.json()).code).toBe("BACKEND_TIMEOUT");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("returns a sanitized unavailable result without retrying a failed mutation", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn().mockRejectedValue(new TypeError("private connection details and credentials"));
    vi.stubGlobal("fetch", upstream);
    const response = await proxyToBackend(new Request("https://ui.test/api/orders", { method: "POST", body: "{}" }), "/api/orders");
    expect(response.status).toBe(503);
    const payload = await response.json();
    expect(payload.code).toBe("BACKEND_UNAVAILABLE");
    expect(JSON.stringify(payload)).not.toContain("private connection");
    expect(payload.message).toContain("unknown");
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(upstream).toHaveBeenCalledTimes(1);
  });

  it("forwards the guest checkout capability without treating it as operator identity", () => {
    const headers = buildBackendHeaders(new Headers({ "X-Checkout-Session": "a".repeat(64) }), { bffSecret: "server-only" });
    expect(headers.get("X-Checkout-Session")).toBe("a".repeat(64));
    expect(headers.get("X-V-Market-Ops-Secret")).toBeNull();
  });

  it("applies the smaller byte budget to JSON mutations", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", upstream);
    for (const size of [65_536, 65_537]) {
      const response = await proxyToBackend(new Request("https://ui.test/api/orders", {
        method: "POST", body: new Uint8Array(size),
      }), "/api/orders");
      expect(response.status).toBe(size === 65_536 ? 204 : 413);
    }
    expect(upstream).toHaveBeenCalledTimes(1);
  });

  it("cancels an unknown-length producer at the byte budget instead of draining the whole stream", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    let produced = 0;
    let cancelled = false;
    const stream = new ReadableStream<Uint8Array>({
      pull(controller) {
        produced++;
        controller.enqueue(new Uint8Array(65_536));
        if (produced === 1000) controller.close();
      },
      cancel() { cancelled = true; },
    });
    const request = new Request("https://ui.test/api/ops/imports", {
      method: "POST", body: stream, duplex: "half",
    } as RequestInit);
    const response = await proxyToBackend(request, "/api/ops/imports", { operations: true });
    expect(response.status).toBe(413);
    expect(cancelled).toBe(true);
    expect(produced).toBeLessThan(1000);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("preserves binary CSV bytes and accepts the exact CSV byte budget", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn().mockResolvedValue(new Response("{}", { status: 202 }));
    vi.stubGlobal("fetch", upstream);
    const bytes = new Uint8Array(5_000_000);
    bytes.set([0x81, 0x5c, 0x82, 0xa0]);
    const response = await proxyToBackend(new Request("https://ui.test/api/ops/imports", {
      method: "POST", headers: { "Content-Type": "text/csv" }, body: bytes,
    }), "/api/ops/imports", { operations: true });
    expect(response.status).toBe(202);
    const forwarded = upstream.mock.calls[0][1].body as Uint8Array;
    expect(forwarded.byteLength).toBe(bytes.byteLength);
    expect(Buffer.from(forwarded).equals(Buffer.from(bytes))).toBe(true);
  });

  it("rejects an oversized CSV without content-length before calling the backend", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn().mockResolvedValue(new Response("{}", { status: 202 }));
    vi.stubGlobal("fetch", upstream);
    const response = await proxyToBackend(new Request("https://ui.test/api/ops/imports", {
      method: "POST", headers: { "Content-Type": "text/csv" }, body: new Uint8Array(5_000_001),
    }), "/api/ops/imports", { operations: true });
    expect(response.status).toBe(413);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("forwards async preference so browser uploads cannot fall back to blocking execution", () => {
    const headers=buildBackendHeaders(new Headers({Prefer:"respond-async"}),{bffSecret:"server-only"});
    expect(headers.get("Prefer")).toBe("respond-async");
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it("rejects a cross-origin mutation before sending service credentials upstream", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    const upstream = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", upstream);
    const response = await proxyToBackend(new Request("https://ui.test/api/auth/login", {
      method: "POST", headers: { Origin: "https://attacker.test" }, body: "ignored",
    }), "/api/auth/login");
    expect(response.status).toBe(403);
    expect(upstream).not.toHaveBeenCalled();
  });

  it("accepts the browser host when Next constructs an internal localhost URL", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const response = await proxyToBackend(new Request("http://localhost:13002/api/auth/login", {
      method: "POST", headers: { Host: "127.0.0.1:13002", Origin: "http://127.0.0.1:13002" }, body: "ignored",
    }), "/api/auth/login");
    expect(response.status).toBe(204);
  });

  it("forwards the session cookie and CSRF token and returns rotated session cookies", async () => {
    vi.stubEnv("BACKEND_ORIGIN", "https://backend.test");
    vi.stubEnv("BFF_SHARED_SECRET", "server-bff");
    const upstream = vi.fn().mockResolvedValue(new Response(null, {
      status: 204,
      headers: { "Set-Cookie": "VMARKET_SESSION=rotated; Path=/; HttpOnly; Secure; SameSite=Lax" },
    }));
    vi.stubGlobal("fetch", upstream);
    const response = await proxyToBackend(new Request("https://ui.test/api/auth/login", {
      method: "POST",
      headers: { Cookie: "VMARKET_SESSION=opaque", "X-CSRF-TOKEN": "csrf-value", "Content-Type": "application/x-www-form-urlencoded" },
      body: "username=operator&password=example",
    }), "/api/auth/login");
    const headers = upstream.mock.calls[0][1].headers as Headers;
    expect(headers.get("Cookie")).toBe("VMARKET_SESSION=opaque");
    expect(headers.get("X-CSRF-TOKEN")).toBe("csrf-value");
    expect(response.headers.get("Set-Cookie")).toContain("VMARKET_SESSION=rotated");
    expect(response.headers.get("X-V-Market-BFF-Secret")).toBeNull();
  });

  it("replaces browser-supplied trust headers with server-owned credentials", () => {
    const incoming = new Headers({
      "Content-Type": "application/json",
      "X-V-Market-Ops-Secret": "attacker-value",
      "X-V-Market-BFF-Secret": "attacker-value",
    });

    const headers = buildBackendHeaders(incoming, {
      bffSecret: "trusted-bff",
      opsSecret: "trusted-ops",
    });

    expect(headers.get("X-V-Market-BFF-Secret")).toBe("trusted-bff");
    expect(headers.get("X-V-Market-Ops-Secret")).toBe("trusted-ops");
    expect(headers.get("Content-Type")).toBe("application/json");
  });
});
