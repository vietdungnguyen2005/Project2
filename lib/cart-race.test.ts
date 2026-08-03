import { describe, expect, it } from "vitest";
import { calculateCartTotals, createOrder, filterProducts } from "@/lib/commerce";
import { readStoredCart, writeStoredCart } from "@/lib/cart-storage";
import { applyQuantity, CartMutationCoordinator, emptyCart } from "@/lib/cart-race";
import { products } from "@/data/products";

describe("CartMutationCoordinator", () => {
  it("aborts the previous request for a product when a new quantity is prepared", () => {
    const coordinator = new CartMutationCoordinator();

    const first = coordinator.prepare("vm-001");
    const second = coordinator.prepare("vm-001");

    expect(first.signal.aborted).toBe(true);
    expect(second.signal.aborted).toBe(false);
    expect(coordinator.isCurrent("vm-001", first.sequence)).toBe(false);
    expect(coordinator.isCurrent("vm-001", second.sequence)).toBe(true);
  });

  it("keeps quantity writes normalized and deterministic", () => {
    const cart = applyQuantity(emptyCart(), "vm-002", 120);
    const updated = applyQuantity(cart, "vm-002", 0);

    expect(cart.lines).toEqual([{ productId: "vm-002", quantity: 99 }]);
    expect(updated.lines).toEqual([]);
  });
});

describe("commerce helpers", () => {
  it("filters and sorts the catalog without mutating source order", () => {
    const filtered = filterProducts(products, "desk", "All", "price-asc");

    expect(filtered.map((product) => product.id)).toEqual(["vm-007", "vm-002"]);
    expect(products[0]?.id).toBe("vm-001");
  });

  it("calculates checkout totals and creates an order reference", () => {
    const cart = applyQuantity(emptyCart(), "vm-001", 2);
    const totals = calculateCartTotals(cart, products);
    const order = createOrder(cart, products, {
      name: "V Market Buyer",
      email: "buyer@example.com",
      address: "1 Market Street",
      city: "Bangkok",
      deliveryWindow: "standard",
      paymentMethod: "invoice",
      privacyAccepted: true,
    });

    expect(totals).toMatchObject({
      subtotal: 148,
      shipping: 0,
      itemCount: 2,
    });
    expect(order.id).toMatch(/^VM-/);
    expect(order.status).toBe("confirmed");
    expect(order.paymentStatus).toBe("pending");
  });
});

describe("cart storage", () => {
  it("round-trips cart state through storage and drops malformed values", () => {
    const store = new Map<string, string>();
    const storage = {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
    };
    const cart = applyQuantity(emptyCart(), "vm-003", 3);

    writeStoredCart(storage, cart);
    expect(readStoredCart(storage).lines).toEqual([{ productId: "vm-003", quantity: 3 }]);

    store.set("v-market:cart:v1", "{broken");
    expect(readStoredCart(storage).lines).toEqual([]);
  });
});
