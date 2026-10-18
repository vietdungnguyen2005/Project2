import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { submitOrder } from "@/lib/order-api";

describe("order API contract", () => {
  it("does not rotate an invalid stored guest identity or send a checkout", async () => {
    tab.set("vmarket:checkout-session:v1", "invalid");
    const upstream = vi.fn();
    vi.stubGlobal("fetch", upstream);
    await expect(submitOrder({ lines: [], updatedAt: "2026-10-18T00:00:00Z" }, {
      name: "Test", email: "test@example.test", postalCode: "100-0001", prefecture: "Tokyo", city: "Tokyo",
      addressLine: "1-1", paymentMethod: "cod", privacyAccepted: true,
    }, "unresolved-key")).rejects.toThrow("Checkout identity cannot be preserved");
    expect(upstream).not.toHaveBeenCalled();
    expect(tab.get("vmarket:checkout-session:v1")).toBe("invalid");
  });

  const tab = new Map<string, string>();
  beforeEach(() => {
    tab.clear();
    vi.stubGlobal("sessionStorage", {
      getItem: (key: string) => tab.get(key) ?? null,
      setItem: (key: string, value: string) => { tab.set(key, value); },
    });
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("uses the same persisted guest capability for a checkout retry", async () => {
    tab.set("vmarket:checkout-session:v1", "a".repeat(64));
    const upstream = vi.fn().mockImplementation(() => Promise.resolve(new Response("{}", { status: 201 })));
    vi.stubGlobal("fetch", upstream);
    const customer = { name: "Test Buyer", email: "buyer@example.test", postalCode: "100-0001", prefecture: "Tokyo", city: "Tokyo", addressLine: "1-1", paymentMethod: "cod" as const, privacyAccepted: true };
    const cart = { lines: [{ productId: "VM-001", quantity: 1 }], updatedAt: "2026-10-18T00:00:00Z" };
    await submitOrder(cart, customer, "same-key");
    await submitOrder(cart, customer, "same-key");
    expect(upstream).toHaveBeenCalledTimes(2);
    for (const call of upstream.mock.calls) {
      expect(new Headers(call[1].headers).get("X-Checkout-Session")).toBe("a".repeat(64));
    }
  });

  it("sends a stable idempotency key and Japanese checkout fields", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          orderNumber: "VM-ABC123",
          trackingToken: "track-token",
          paymentStatus: "PENDING",
          fulfillmentStatus: "RECEIVED",
          subtotalMinor: 11_000,
          shippingMinor: 0,
          taxMinor: 880,
          grandTotalMinor: 11_880,
          currency: "JPY",
          createdAt: "2026-08-09T00:00:00Z",
        }),
        { status: 201, headers: { "Content-Type": "application/json" } },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    await submitOrder(
      { lines: [{ productId: "VM-001", quantity: 1 }], updatedAt: "2026-08-09T00:00:00Z" },
      {
        name: "Yuki Tanaka",
        email: "yuki@example.jp",
        postalCode: "100-0001",
        prefecture: "Tokyo",
        city: "Chiyoda-ku",
        addressLine: "Chiyoda 1-1",
        paymentMethod: "cod",
        privacyAccepted: true,
      },
      "checkout-001",
    );

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(new Headers(options.headers).get("Idempotency-Key")).toBe("checkout-001");
    expect(JSON.parse(options.body as string)).toMatchObject({
      customer: { postalCode: "100-0001", paymentMethod: "COD" },
      lines: [{ sku: "VM-001", quantity: 1 }],
    });
  });
});
