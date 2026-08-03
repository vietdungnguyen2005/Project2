import { applyQuantity } from "@/lib/cart-race";
import type { Cart } from "@/types/commerce";

export async function updateCartQuantity(
  cart: Cart,
  productId: string,
  quantity: number,
  signal: AbortSignal,
): Promise<Cart> {
  const response = await fetch("/api/cart/quantity", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-V-Market-Mutation": `${productId}:${quantity}`,
    },
    body: JSON.stringify({ cart, productId, quantity }),
    signal,
  });

  if (!response.ok) {
    throw new Error("Cart update failed");
  }

  const payload = (await response.json()) as Cart;
  return applyQuantity(payload, productId, quantity);
}
