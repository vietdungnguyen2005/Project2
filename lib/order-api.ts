import type { Cart, CheckoutForm, Order } from "@/types/commerce";

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
