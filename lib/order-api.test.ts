import { describe, expect, it, vi } from "vitest";
import { submitOrder } from "@/lib/order-api";

describe("order API contract", () => {
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
