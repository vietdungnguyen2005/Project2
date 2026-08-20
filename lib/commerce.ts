import type { Cart, CartTotals, Product } from "@/types/commerce";

export type SortMode = "featured" | "price-asc" | "stock-desc";

export const categories = ["All", "Apparel", "Home", "Office", "Travel"] as const;
export type CategoryFilter = (typeof categories)[number];

export function formatCurrency(value: number): string {
  return new Intl.NumberFormat("ja-JP", {
    style: "currency",
    currency: "JPY",
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

    if (sortMode === "stock-desc") {
      return b.inventory - a.inventory;
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
  const shipping = itemCount === 0 || subtotal >= 12_000 ? 0 : 900;
  const tax = Math.round(subtotal * 0.08 * 100) / 100;

  return {
    subtotal,
    shipping,
    tax,
    grandTotal: subtotal + shipping + tax,
    itemCount,
  };
}
