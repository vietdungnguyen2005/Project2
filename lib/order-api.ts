import type { Cart, CheckoutForm, Order } from "@/types/commerce";

export type OrderResponse = Order & {
  storage: "r2" | "local-fallback";
};

export async function submitOrder(cart: Cart, customer: CheckoutForm): Promise<OrderResponse> {
  const response = await fetch("/api/orders", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ cart, customer }),
  });

  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as
      | { message?: string; errors?: string[] }
      | null;
    const details = payload?.errors?.length ? ` ${payload.errors.join(" ")}` : "";

    throw new Error(`${payload?.message ?? "Order submission failed"}.${details}`);
  }

  return (await response.json()) as OrderResponse;
}
