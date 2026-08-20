import { describe, expect, it } from "vitest";
import { calculateCartTotals, filterProducts } from "@/lib/commerce";
import { readStoredCart, writeStoredCart } from "@/lib/cart-storage";
import { applyQuantity, CartMutationCoordinator, emptyCart } from "@/lib/cart-race";
import type { Product } from "@/types/commerce";

const products: Product[] = [
  {
    id: "VM-001",
    vendor: "Nami Studio",
    name: "AeroKnit travel jacket",
    category: "Apparel",
    description: "Travel jacket",
    price: 11_000,
    inventory: 32,
    currency: "JPY",
    image: { src: "/products/aeroknit-travel-jacket.jpg", alt: "Travel jacket" },
  },
  {
    id: "VM-007",
    vendor: "Orbit Works",
    name: "Magnetic cable dock",
    category: "Office",
    description: "Desk cable dock",
    price: 3_600,
    inventory: 84,
    currency: "JPY",
    image: { src: "/products/magnetic-cable-dock.jpg", alt: "Cable dock" },
  },
  {
    id: "VM-002",
    vendor: "Riverbyte",
    name: "Modular desk organizer",
    category: "Office",
    description: "Stackable desk trays",
    price: 5_700,
    inventory: 58,
    currency: "JPY",
    image: { src: "/products/modular-desk-organizer.jpg", alt: "Desk organizer" },
  },
];

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

    expect(filtered.map((product) => product.id)).toEqual(["VM-007", "VM-002"]);
    expect(products[0]?.id).toBe("VM-001");
  });

  it("calculates the same JPY checkout totals as the backend policy", () => {
    const cart = applyQuantity(emptyCart(), "VM-001", 2);
    const totals = calculateCartTotals(cart, products);

    expect(totals).toMatchObject({
      subtotal: 22_000,
      shipping: 0,
      itemCount: 2,
    });
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
