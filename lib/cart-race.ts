import type { Cart } from "@/types/commerce";

export type QuantityPayload = {
  productId: string;
  quantity: number;
  signal: AbortSignal;
  sequence: number;
};

export class CartMutationCoordinator {
  private controllers = new Map<string, AbortController>();
  private sequences = new Map<string, number>();

  prepare(productId: string): QuantityPayload {
    this.controllers.get(productId)?.abort();

    const controller = new AbortController();
    const sequence = (this.sequences.get(productId) ?? 0) + 1;

    this.controllers.set(productId, controller);
    this.sequences.set(productId, sequence);

    return {
      productId,
      quantity: 0,
      signal: controller.signal,
      sequence,
    };
  }

  withQuantity(payload: QuantityPayload, quantity: number): QuantityPayload {
    return { ...payload, quantity };
  }

  isCurrent(productId: string, sequence: number): boolean {
    return this.sequences.get(productId) === sequence;
  }

  settle(productId: string, sequence: number): void {
    if (this.isCurrent(productId, sequence)) {
      this.controllers.delete(productId);
    }
  }
}

export function applyQuantity(cart: Cart, productId: string, quantity: number): Cart {
  const normalizedQuantity = Math.min(99, Math.max(0, Math.floor(quantity)));
  const lines = cart.lines.filter((line) => line.productId !== productId);

  if (normalizedQuantity > 0) {
    lines.push({ productId, quantity: normalizedQuantity });
  }

  return {
    lines: lines.sort((a, b) => a.productId.localeCompare(b.productId)),
    updatedAt: new Date().toISOString(),
  };
}

export const emptyCart = (): Cart => ({
  lines: [],
  updatedAt: new Date().toISOString(),
});
