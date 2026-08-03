import type { Cart } from "@/types/commerce";
import { emptyCart } from "@/lib/cart-race";

const CART_STORAGE_KEY = "v-market:cart:v1";

export function readStoredCart(storage: Pick<Storage, "getItem">): Cart {
  const rawValue = storage.getItem(CART_STORAGE_KEY);

  if (!rawValue) {
    return emptyCart();
  }

  try {
    const parsed = JSON.parse(rawValue) as Cart;
    if (!Array.isArray(parsed.lines) || typeof parsed.updatedAt !== "string") {
      return emptyCart();
    }

    return {
      lines: parsed.lines
        .filter(
          (line) =>
            typeof line.productId === "string" &&
            Number.isFinite(line.quantity) &&
            line.quantity > 0,
        )
        .map((line) => ({
          productId: line.productId,
          quantity: Math.min(99, Math.floor(line.quantity)),
        })),
      updatedAt: parsed.updatedAt,
    };
  } catch {
    return emptyCart();
  }
}

export function writeStoredCart(storage: Pick<Storage, "setItem">, cart: Cart): void {
  storage.setItem(CART_STORAGE_KEY, JSON.stringify(cart));
}
