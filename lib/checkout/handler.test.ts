import { describe, expect, it, vi } from "vitest";
import { CheckoutHandler } from "./handler";
import { checkoutStorageKey, guestStorageKey } from "./spec";

async function digest(value: string) {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))), byte => byte.toString(16).padStart(2, "0")).join("");
}
function tabStorage() {
  const data = new Map<string, string>();
  return { data, getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value); }, removeItem: (key: string) => { data.delete(key); } };
}
const customer = { name: "Test Buyer", email: "buyer@example.test", postalCode: "100-0001", prefecture: "Tokyo", city: "Chiyoda-ku", addressLine: "Chiyoda 1-1", paymentMethod: "cod", privacyAccepted: true };
const cart = { lines: [{ productId: "VM-004", quantity: 1 }], updatedAt: "2026-10-18T00:00:00Z" };
const order = { orderNumber: "VM-RECOVERED", trackingToken: "f".repeat(64), paymentStatus: "PENDING", fulfillmentStatus: "RECEIVED", subtotalMinor: 4300, shippingMinor: 900, taxMinor: 344, grandTotalMinor: 5544, currency: "JPY", createdAt: "2026-10-18T00:00:00Z" };

describe("checkout operation recovery", () => {
  it("keeps the same operation after a BFF timeout and recovers it without another POST", async () => {
    const storage = tabStorage();
    const request = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "BACKEND_TIMEOUT" }), { status: 504 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(order), { status: 200 }));
    const handler = new CheckoutHandler({ storage, request });
    const timedOut = await handler.execute({ action: "submit", cart, customer });
    expect(timedOut.success).toBe(false);
    expect(timedOut.state.status).toBe("unresolved");
    const key = JSON.parse(storage.getItem(checkoutStorageKey)!).key;
    await new CheckoutHandler({ storage, request }).execute({ action: "restore" });
    expect(request).toHaveBeenCalledTimes(1);
    const recovered = await handler.execute({ action: "recover" });
    expect(recovered.success && recovered.state.status).toBe("confirmed");
    expect(request.mock.calls[1][0]).toBe("/api/orders/checkout");
    expect(new Headers(request.mock.calls[1][1].headers).get("Idempotency-Key")).toBe(key);
  });

  it("keeps an unobserved operation immutable until durable closure, including after a lost close response", async () => {
    const storage = tabStorage();
    const request = vi.fn()
      .mockRejectedValueOnce(new TypeError("lost create response"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "CHECKOUT_NOT_OBSERVED" }), { status: 404 }))
      .mockRejectedValueOnce(new TypeError("lost close response"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: "CHECKOUT_CANCELLED" }), { status: 410 }));
    const handler = new CheckoutHandler({ storage, request });
    await handler.execute({ action: "submit", cart, customer });
    const original = storage.getItem(checkoutStorageKey);
    const changed = await handler.execute({ action: "submit", cart, customer: { ...customer, city: "Osaka" } });
    expect(!changed.success && changed.error.code).toBe("PAYLOAD_CHANGED");
    expect(request).toHaveBeenCalledTimes(1);
    const missing = await handler.execute({ action: "recover" });
    expect(!missing.success && missing.error.code).toBe("NOT_OBSERVED");
    expect((await handler.execute({ action: "acknowledge" })).success).toBe(false);
    expect(storage.getItem(checkoutStorageKey)).toBe(original);
    const lost = await handler.execute({ action: "close" });
    expect(!lost.success && lost.error.code).toBe("OUTCOME_UNKNOWN");
    expect(storage.getItem(checkoutStorageKey)).toBe(original);
    const recovered = await new CheckoutHandler({ storage, request }).execute({ action: "recover" });
    expect(recovered.success && recovered.state.status).toBe("closed");
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).key).toBe(JSON.parse(original!).key);
  });

  it("does not confirm an in-flight response after its guest identity is replaced", async () => {
    const storage = tabStorage();
    const request = vi.fn(async () => {
      storage.setItem(guestStorageKey, "b".repeat(64));
      return new Response(JSON.stringify(order), { status: 201 });
    });
    const result = await new CheckoutHandler({ storage, request }).execute({ action: "submit", cart, customer });
    expect(!result.success && result.error.code).toBe("IDENTITY_CHANGED");
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).phase).toBe("unresolved");
  });

  it("does not resend an existing key if the guest capability changes while preparing the request", async () => {
    const storage = tabStorage();
    await new CheckoutHandler({ storage, request: vi.fn().mockRejectedValue(new TypeError("lost")) }).execute({ action: "submit", cart, customer });
    const originalRead = storage.getItem;
    let guestReads = 0;
    storage.getItem = key => {
      if (key === guestStorageKey && ++guestReads === 2) storage.data.set(guestStorageKey, "b".repeat(64));
      return originalRead(key);
    };
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(order), { status: 201 }));
    const result = await new CheckoutHandler({ storage, request }).execute({ action: "submit", cart, customer });
    expect(!result.success && result.error.code).toBe("IDENTITY_CHANGED");
    expect(request).not.toHaveBeenCalled();
  });

  it("does not let a late response from an old component overwrite a newer checkout", async () => {
    const storage = tabStorage();
    let release!: (response: Response) => void;
    let reached!: () => void;
    const sent = new Promise<void>(resolve => { reached = resolve; });
    const oldHandler = new CheckoutHandler({ storage, request: async () => {
      reached(); return new Promise<Response>(resolve => { release = resolve; });
    } });
    const old = oldHandler.execute({ action: "submit", cart, customer });
    await sent;
    const newer = new CheckoutHandler({ storage, request: vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(order), { status: 200 }))
      .mockRejectedValueOnce(new TypeError("new response lost")) });
    await newer.execute({ action: "recover" });
    await newer.execute({ action: "acknowledge" });
    await newer.execute({ action: "submit", cart, customer });
    const newKey = JSON.parse(storage.getItem(checkoutStorageKey)!).key;
    release(new Response(JSON.stringify(order), { status: 201 }));
    await old;
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).key).toBe(newKey);
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).phase).toBe("unresolved");
  });

  it("requires a durable close response and explicit acknowledgement before allowing a new key", async () => {
    const storage = tabStorage();
    const request = vi.fn().mockRejectedValueOnce(new TypeError("response lost")).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const handler = new CheckoutHandler({ storage, request });
    await handler.execute({ action: "submit", cart, customer });
    const key = JSON.parse(storage.getItem(checkoutStorageKey)!).key;
    expect((await handler.execute({ action: "acknowledge" })).success).toBe(false);
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).key).toBe(key);
    const closed = await handler.execute({ action: "close" });
    expect(closed.success && closed.state.status).toBe("closed");
    expect(request.mock.calls[1][0]).toBe("/api/orders/checkout/cancel");
    expect(new Headers(request.mock.calls[1][1].headers).get("Idempotency-Key")).toBe(key);
    expect(JSON.parse(storage.getItem(checkoutStorageKey)!).phase).toBe("closed");
    expect(await handler.execute({ action: "acknowledge" })).toEqual({ success: true, state: { status: "idle" } });
    expect(storage.getItem(checkoutStorageKey)).toBeNull();
  });

  it("recovers a lost response after reload with GET and no saved customer payload", async () => {
    const storage = tabStorage();
    const first = new CheckoutHandler({ storage, request: vi.fn().mockRejectedValue(new TypeError("network lost")) });
    const lost = await first.execute({ action: "submit", cart, customer });
    expect(!lost.success && lost.error.code).toBe("OUTCOME_UNKNOWN");
    const key = JSON.parse(storage.getItem(checkoutStorageKey)!).key;
    const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(order), { status: 200 }));
    const afterReload = new CheckoutHandler({ storage, request });
    const recovered = await afterReload.execute({ action: "recover" });
    expect(recovered.success && recovered.state.status).toBe("confirmed");
    expect(request).toHaveBeenCalledTimes(1);
    expect(request.mock.calls[0][0]).toBe("/api/orders/checkout");
    expect(request.mock.calls[0][1].method).toBe("GET");
    expect(request.mock.calls[0][1].body).toBeUndefined();
    expect(new Headers(request.mock.calls[0][1].headers).get("Idempotency-Key")).toBe(key);
  });

  it("durably records a non-PII operation identity before sending and keeps a confirmed marker", async () => {
    const storage = tabStorage();
    const request = vi.fn(async () => {
      const raw = storage.getItem(checkoutStorageKey)!;
      expect(JSON.parse(raw).phase).toBe("unresolved");
      expect(raw).not.toContain(customer.email);
      expect(raw).not.toContain(customer.name);
      expect(raw).not.toContain(customer.addressLine);
      return new Response(JSON.stringify(order), { status: 201 });
    });
    const handler = new CheckoutHandler({ storage, request });
    const result = await handler.execute({ action: "submit", cart, customer });
    expect(result.success && result.state.status).toBe("confirmed");
    expect(request).toHaveBeenCalledTimes(1);
    const saved = JSON.parse(storage.getItem(checkoutStorageKey)!);
    expect(saved.phase).toBe("confirmed");
    expect(saved).not.toHaveProperty("order");
    expect(saved).not.toHaveProperty("trackingToken");
  });

  it("restores an unresolved identity after reload without sending any request", async () => {
    const storage = tabStorage();
    storage.setItem(guestStorageKey, "a".repeat(64));
    const operation = { version: 1, key: "11111111-1111-4111-8111-111111111111", payloadHash: "b".repeat(64),
      capabilityHash: await digest("a".repeat(64)), phase: "unresolved", createdAt: "2026-10-18T00:00:00Z" };
    storage.setItem(checkoutStorageKey, JSON.stringify(operation));
    const request = vi.fn();
    const handler = new CheckoutHandler({ storage, request });
    expect(await handler.execute({ action: "restore" })).toEqual({ success: true, state: { status: "unresolved", operation } });
    expect(request).not.toHaveBeenCalled();
  });
});
