import type { Cart, CartTotals, CheckoutForm, Order, Product } from "@/types/commerce";

export type SortMode = "featured" | "price-asc" | "rating-desc";

export const categories = ["All", "Apparel", "Home", "Office", "Travel"] as const;
export type CategoryFilter = (typeof categories)[number];

export function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  }).format(value);
}

export function filterProducts(
  items: Product[],
  query: string,
  category: CategoryFilter,
  sortMode: SortMode,
): Product[] {
  const normalizedQuery = query.trim().toLowerCase();
  const featuredIndex = new Map(items.map((product, index) => [product.id, index]));

  const filtered = items.filter((product) => {
    const matchesCategory = category === "All" || product.category === category;
    const searchable = `${product.name} ${product.vendor} ${product.description}`.toLowerCase();
    return matchesCategory && (!normalizedQuery || searchable.includes(normalizedQuery));
  });

  return [...filtered].sort((a, b) => {
    if (sortMode === "price-asc") {
      return a.price - b.price;
    }

    if (sortMode === "rating-desc") {
      return b.rating - a.rating;
    }

    return (featuredIndex.get(a.id) ?? 0) - (featuredIndex.get(b.id) ?? 0);
  });
}

export function calculateCartTotals(cart: Cart, catalog: Product[]): CartTotals {
  const subtotal = cart.lines.reduce((total, line) => {
    const product = catalog.find((item) => item.id === line.productId);
    return total + (product?.price ?? 0) * line.quantity;
  }, 0);

  const itemCount = cart.lines.reduce((total, line) => total + line.quantity, 0);
  const shipping = itemCount === 0 || subtotal >= 120 ? 0 : 9;
  const tax = Math.round(subtotal * 0.08 * 100) / 100;

  return {
    subtotal,
    shipping,
    tax,
    grandTotal: subtotal + shipping + tax,
    itemCount,
  };
}

export function createOrder(cart: Cart, catalog: Product[], customer: CheckoutForm): Order {
  const id = `VM-${Date.now().toString(36).toUpperCase()}`;
  const createdAt = new Date().toISOString();

  return {
    id,
    cart,
    totals: calculateCartTotals(cart, catalog),
    customer,
    createdAt,
    privacyAcceptedAt: createdAt,
    status: "confirmed",
    paymentStatus: "pending",
    fulfillmentStatus: "received",
  };
}
