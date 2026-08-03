import { describe, expect, it } from "vitest";
import { products } from "@/data/products";
import { buildOrderFromRequest, normalizeOrderRequest } from "@/lib/order-validation";

const validPayload = {
  cart: {
    lines: [{ productId: "vm-001", quantity: 2 }],
    updatedAt: "2026-07-24T00:00:00.000Z",
  },
  customer: {
    name: "V Market Buyer",
    email: "BUYER@EXAMPLE.COM",
    address: "1 Market Street",
    city: "Bangkok",
    deliveryWindow: "standard",
    paymentMethod: "invoice",
    privacyAccepted: true,
  },
};

describe("order validation", () => {
  it("normalizes a valid order payload before order creation", () => {
    const result = buildOrderFromRequest(validPayload, products);

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    expect(result.order.cart.lines).toEqual([{ productId: "vm-001", quantity: 2 }]);
    expect(result.order.customer.email).toBe("buyer@example.com");
    expect(result.order.totals.subtotal).toBe(148);
    expect(result.order.paymentStatus).toBe("pending");
  });

  it("rejects unknown catalog items", () => {
    const result = normalizeOrderRequest(
      {
        ...validPayload,
        cart: { lines: [{ productId: "vm-404", quantity: 1 }] },
      },
      products,
    );

    expect(result.errors).toContain("Cart line 1 references an unknown product.");
  });

  it("rejects over-inventory quantities", () => {
    const result = normalizeOrderRequest(
      {
        ...validPayload,
        cart: { lines: [{ productId: "vm-003", quantity: 19 }] },
      },
      products,
    );

    expect(result.errors).toContain("Ceramic pour-over set exceeds available inventory.");
  });

  it("rejects invalid customer and privacy fields", () => {
    const result = normalizeOrderRequest(
      {
        ...validPayload,
        customer: {
          ...validPayload.customer,
          email: "not-an-email",
          privacyAccepted: false,
        },
      },
      products,
    );

    expect(result.errors).toContain("A valid email address is required.");
    expect(result.errors).toContain("Privacy acknowledgement is required.");
  });

  it("rejects unsupported payment methods", () => {
    const result = normalizeOrderRequest(
      {
        ...validPayload,
        customer: {
          ...validPayload.customer,
          paymentMethod: "card-token-that-was-never-authorized",
        },
      },
      products,
    );

    expect(result.errors).toContain("Payment method is not supported.");
  });
});
