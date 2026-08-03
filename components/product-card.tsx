"use client";

import { Minus, Plus, ShoppingBag, Star } from "lucide-react";
import Image from "next/image";
import { formatCurrency } from "@/lib/commerce";
import type { Product } from "@/types/commerce";

type ProductCardProps = {
  product: Product;
  index: number;
  quantity: number;
  onQuantityChange: (productId: string, quantity: number) => void;
};

export function ProductCard({
  product,
  index,
  quantity,
  onQuantityChange,
}: ProductCardProps) {
  const isAboveFold = index < 2;

  return (
    <article className="group grid min-h-full grid-rows-[auto_1fr] overflow-hidden rounded-lg border border-zinc-200 bg-white shadow-sm transition duration-200 hover:-translate-y-0.5 hover:shadow-xl">
      <div className="relative aspect-[4/5] overflow-hidden bg-zinc-100">
        <Image
          src={product.image.src}
          alt={product.image.alt}
          width={product.image.width}
          height={product.image.height}
          sizes="(max-width: 640px) 92vw, (max-width: 1024px) 44vw, 23vw"
          priority={isAboveFold}
          className="h-full w-full object-cover transition duration-500 group-hover:scale-[1.035]"
        />
        <div className="absolute left-3 top-3 rounded-full bg-white/90 px-3 py-1 text-xs font-semibold text-zinc-900 shadow-sm backdrop-blur">
          {product.badge}
        </div>
      </div>

      <div className="grid gap-4 p-4">
        <div className="grid gap-2">
          <div className="flex items-center justify-between gap-3 text-sm text-zinc-500">
            <span>{product.vendor}</span>
            <span className="inline-flex items-center gap-1 text-zinc-700">
              <Star aria-hidden className="size-4 fill-amber-400 text-amber-400" />
              {product.rating}
            </span>
          </div>
          <h2 className="text-balance text-lg font-semibold leading-tight text-zinc-950">
            {product.name}
          </h2>
          <p className="text-sm leading-6 text-zinc-500">{product.description}</p>
          <div className="flex flex-wrap items-center gap-2 text-xs font-semibold text-zinc-600">
            <span className="rounded-full bg-zinc-100 px-2 py-1">{product.category}</span>
            <span className="rounded-full bg-emerald-50 px-2 py-1 text-emerald-700">
              {product.shippingSla}
            </span>
            <span>{product.reviewCount.toLocaleString()} reviews</span>
          </div>
        </div>

        <div className="mt-auto flex items-center justify-between gap-3">
          <div>
            <p className="text-xl font-bold text-zinc-950">{formatCurrency(product.price)}</p>
            <p className="text-xs text-zinc-500">{product.inventory} in stock</p>
          </div>
          {quantity === 0 ? (
            <button
              type="button"
              onClick={() => onQuantityChange(product.id, 1)}
              className="inline-flex h-11 items-center gap-2 rounded-md bg-zinc-950 px-4 text-sm font-semibold text-white transition hover:bg-emerald-700 focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:ring-offset-2"
            >
              <ShoppingBag aria-hidden className="size-4" />
              Add
            </button>
          ) : (
            <div className="grid h-11 grid-cols-[2.75rem_2.75rem_2.75rem] overflow-hidden rounded-md border border-zinc-200 bg-zinc-50">
              <button
                type="button"
                aria-label={`Decrease ${product.name} quantity`}
                onClick={() => onQuantityChange(product.id, quantity - 1)}
                className="grid place-items-center transition hover:bg-white focus:outline-none focus:ring-2 focus:ring-inset focus:ring-emerald-500"
              >
                <Minus aria-hidden className="size-4" />
              </button>
              <output
                aria-label={`${product.name} quantity`}
                className="grid place-items-center border-x border-zinc-200 text-sm font-bold text-zinc-950"
              >
                {quantity}
              </output>
              <button
                type="button"
                aria-label={`Increase ${product.name} quantity`}
                onClick={() => onQuantityChange(product.id, quantity + 1)}
                className="grid place-items-center transition hover:bg-white focus:outline-none focus:ring-2 focus:ring-inset focus:ring-emerald-500"
              >
                <Plus aria-hidden className="size-4" />
              </button>
            </div>
          )}
        </div>
      </div>
    </article>
  );
}
