"use client";

import { Search } from "lucide-react";
import { categories, type CategoryFilter, type SortMode } from "@/lib/commerce";

type CatalogControlsProps = {
  category: CategoryFilter;
  isUpdating: boolean;
  productCount: number;
  query: string;
  sortMode: SortMode;
  onCategoryChange: (category: CategoryFilter) => void;
  onQueryChange: (query: string) => void;
  onSortModeChange: (sortMode: SortMode) => void;
};

export function CatalogControls({
  category,
  isUpdating,
  productCount,
  query,
  sortMode,
  onCategoryChange,
  onQueryChange,
  onSortModeChange,
}: CatalogControlsProps) {
  return (
    <div className="mb-6 grid gap-4">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <p className="text-sm font-bold uppercase tracking-[0.16em] text-emerald-700">
            Curated vendors
          </p>
          <h2 className="mt-2 text-3xl font-black text-zinc-950">Mobile-fast catalog</h2>
        </div>
        <p aria-live="polite" className="text-sm font-medium text-zinc-600">
          {isUpdating ? "Syncing cart..." : `${productCount} products ready`}
        </p>
      </div>

      <div className="grid gap-3 rounded-lg border border-zinc-200 bg-zinc-50 p-3 sm:grid-cols-[1fr_12rem]">
        <label className="relative block" htmlFor="catalog-search">
          <span className="sr-only">Search catalog</span>
          <Search
            aria-hidden
            className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-zinc-500"
          />
          <input
            id="catalog-search"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="Search products or vendors"
            className="h-11 w-full rounded-md border border-zinc-200 bg-white pl-10 pr-3 text-sm text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
          />
        </label>
        <label className="block" htmlFor="catalog-sort">
          <span className="sr-only">Sort catalog</span>
          <select
            id="catalog-sort"
            value={sortMode}
            onChange={(event) => onSortModeChange(event.target.value as SortMode)}
            className="h-11 w-full rounded-md border border-zinc-200 bg-white px-3 text-sm font-semibold text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
          >
            <option value="featured">Featured</option>
            <option value="price-asc">Price: low to high</option>
            <option value="stock-desc">Stock: high to low</option>
          </select>
        </label>
      </div>

      <div className="flex gap-2 overflow-x-auto pb-1" aria-label="Catalog categories">
        {categories.map((item) => (
          <button
            key={item}
            type="button"
            onClick={() => onCategoryChange(item)}
            className={`h-10 shrink-0 rounded-md border px-4 text-sm font-bold transition ${
              category === item
                ? "border-zinc-950 bg-zinc-950 text-white"
                : "border-zinc-200 bg-white text-zinc-700 hover:border-zinc-950"
            }`}
          >
            {item}
          </button>
        ))}
      </div>
    </div>
  );
}
