import type { Cart, CheckoutForm, Order } from "@/types/commerce";

// A guest bearer namespace, not a verified user identity. Never send it in URLs or logs.
function guestCheckoutCapability(): string {
  const key = "vmarket:checkout-session:v1";
  try {
    const existing = sessionStorage.getItem(key);
    if (existing !== null) {
      if (!/^[0-9a-f]{64}$/.test(existing)) throw new Error("Invalid checkout identity");
      return existing;
    }
    const capability = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, "0")).join("");
    sessionStorage.setItem(key, capability);
    return capability;
  } catch {
    throw new Error("Checkout identity cannot be preserved in this tab. Do not retry an unresolved order with a new identity; restore tab storage or request operator review.");
  }
}

export async function submitOrder(
  cart: Cart,
  customer: CheckoutForm,
  idempotencyKey = crypto.randomUUID(),
): Promise<Order> {
  const response = await fetch("/api/orders", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": idempotencyKey,
      "X-Checkout-Session": guestCheckoutCapability(),
    },
    body: JSON.stringify({
      customer: {
        name: customer.name,
        email: customer.email,
        postalCode: customer.postalCode,
        prefecture: customer.prefecture,
        city: customer.city,
        addressLine: customer.addressLine,
        paymentMethod: customer.paymentMethod.toUpperCase(),
        privacyAccepted: customer.privacyAccepted,
      },
      lines: cart.lines.map((line) => ({
        sku: line.productId,
        quantity: line.quantity,
      })),
    }),
  });

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as
      | { message?: string; details?: string[] }
      | null;
    const details = payload?.details?.length ? ` ${payload.details.join(" ")}` : "";
    throw new Error(`${payload?.message ?? "Order submission failed."}${details}`);
  }

  return (await response.json()) as Order;
}
