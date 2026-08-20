"use client";

import { Gauge, Search, ShieldCheck, ShoppingCart, Truck, type LucideIcon } from "lucide-react";
import Image from "next/image";
import type { Product } from "@/types/commerce";

type Guarantee = {
  icon: LucideIcon;
  label: string;
  value: string;
};

const guarantees: Guarantee[] = [
  { icon: Gauge, label: "Legacy intake", value: "UTF-8 + CP932" },
  { icon: ShieldCheck, label: "Race-safe order", value: "Locks + idempotency" },
  { icon: Truck, label: "Audited delivery", value: "Forward-only states" },
];

type HeroSectionProps = {
  heroProduct?: Product;
  totalItems: number;
};

export function HeroSection({ heroProduct, totalItems }: HeroSectionProps) {
  return (
    <section className="relative overflow-hidden border-b border-zinc-200 bg-[#f7f5ef]">
      <div className="mx-auto grid min-h-[92svh] max-w-7xl items-center gap-10 px-4 py-6 sm:px-6 lg:grid-cols-[0.9fr_1.1fr] lg:px-8">
        <div className="z-10 grid gap-7 py-8">
          <nav aria-label="Marketplace controls" className="flex items-center justify-between gap-3">
            <a href="#catalog" className="text-xl font-black tracking-normal text-zinc-950">
              V-Market
            </a>
            <div className="flex items-center gap-2">
              <a
                href="/ops"
                className="hidden h-11 items-center rounded-md border border-zinc-300 bg-white px-3 text-xs font-black uppercase tracking-[0.12em] text-zinc-800 transition hover:border-zinc-950 sm:inline-flex"
              >
                Migration ops
              </a>
              <a href="/track" className="hidden h-11 items-center px-2 text-xs font-black uppercase tracking-[0.12em] text-zinc-700 hover:text-zinc-950 lg:inline-flex">Track</a>
              <a
                href="#catalog-search"
                aria-label="Search products"
                className="grid size-11 place-items-center rounded-md border border-zinc-300 bg-white text-zinc-800 shadow-sm transition hover:border-zinc-950 focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:ring-offset-2"
              >
                <Search aria-hidden className="size-5" />
              </a>
              <a
                href="#checkout"
                aria-label={`${totalItems} items in cart`}
                className="inline-flex h-11 min-w-16 items-center justify-center gap-2 rounded-md bg-zinc-950 px-3 text-sm font-bold text-white"
              >
                <ShoppingCart aria-hidden className="size-4" />
                {totalItems}
              </a>
            </div>
          </nav>

          <div className="grid max-w-2xl gap-5">
            <p className="text-sm font-bold uppercase tracking-[0.18em] text-emerald-700">
              Commerce modernization without hidden drift
            </p>
            <h1 className="text-balance text-5xl font-black leading-[0.95] text-zinc-950 sm:text-6xl lg:text-7xl">
              V-Market
            </h1>
            <p className="max-w-xl text-pretty text-lg leading-8 text-zinc-700">
              A working storefront backed by transactional inventory, opaque order tracking,
              restartable Japanese legacy imports, and measurable reconciliation.
            </p>
          </div>

          <div className="grid gap-3 sm:grid-cols-3">
            {guarantees.map(({ icon: Icon, label, value }) => (
              <div key={label} className="border-l-2 border-zinc-950 pl-3">
                <Icon aria-hidden className="mb-2 size-5 text-emerald-700" />
                <p className="text-sm font-bold text-zinc-950">{label}</p>
                <p className="text-xs text-zinc-600">{value}</p>
              </div>
            ))}
          </div>
        </div>

        <div className="relative min-h-[48svh] lg:min-h-[78svh]">
          {heroProduct ? (
            <Image
              src={heroProduct.image.src}
              alt={heroProduct.image.alt}
              fill
              sizes="(max-width: 1024px) 92vw, 54vw"
              priority
              className="rounded-lg object-cover shadow-2xl"
            />
          ) : (
            <div className="absolute inset-0 rounded-lg bg-[repeating-linear-gradient(135deg,#18181b_0,#18181b_12px,#27272a_12px,#27272a_24px)]" />
          )}
          <div className="absolute bottom-4 left-4 right-4 rounded-md bg-white/92 p-4 shadow-xl backdrop-blur sm:left-auto sm:w-80">
            <p className="text-xs font-bold uppercase tracking-[0.14em] text-emerald-700">
              Canonical product
            </p>
            <p className="mt-1 text-lg font-black text-zinc-950">
              {heroProduct?.name ?? "Canonical catalog connecting"}
            </p>
            <p className="mt-2 text-sm text-zinc-600">
              Price and available quantity are served by Spring Boot from PostgreSQL, with Redis as
              a fail-open cache.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
