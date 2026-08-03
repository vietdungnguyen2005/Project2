"use client";

import { ProductCard } from "@/components/product-card";
import type { Product } from "@/types/commerce";

type CatalogGridProps = {
  products: Product[];
  getQuantity: (productId: string) => number;
  onQuantityChange: (productId: string, quantity: number) => void;
};

export function CatalogGrid({
  products,
  getQuantity,
  onQuantityChange,
}: CatalogGridProps) {
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {products.map((product, index) => (
        <ProductCard
          key={product.id}
          product={product}
          index={index}
          quantity={getQuantity(product.id)}
          onQuantityChange={onQuantityChange}
        />
      ))}
    </div>
  );
}
