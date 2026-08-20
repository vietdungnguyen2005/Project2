"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Database, PackageCheck } from "lucide-react";
import Image from "next/image";
import { loadCatalog } from "@/lib/catalog-api";
import { formatCurrency } from "@/lib/commerce";

export function ProductDetail({ sku }: { sku: string }) {
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: ({ signal }) => loadCatalog(signal) });
  const product = catalog.data?.find((item) => item.id === sku);

  if (catalog.isLoading) return <main className="grid min-h-screen place-items-center bg-[#f7f5ef] font-bold">Loading canonical product…</main>;
  if (catalog.error || !product) return <main className="grid min-h-screen place-items-center bg-[#f7f5ef] p-6 text-center"><div><h1 className="text-4xl font-black">Product unavailable</h1><p className="mt-3 text-zinc-600">{catalog.error instanceof Error ? catalog.error.message : "This SKU is not active."}</p><a href="/" className="mt-6 inline-flex min-h-11 items-center font-bold underline">Back to catalog</a></div></main>;

  return (
    <main className="min-h-screen bg-[#f7f5ef] text-zinc-950">
      <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:px-8">
        <a href="/" className="inline-flex min-h-11 items-center gap-2 text-sm font-black uppercase tracking-wider text-zinc-600 hover:text-zinc-950"><ArrowLeft aria-hidden className="size-4" /> Catalog</a>
        <article className="mt-8 grid overflow-hidden border border-zinc-300 bg-white shadow-[10px_10px_0_#18181b] md:grid-cols-2">
          <div className="relative min-h-[28rem] bg-zinc-100"><Image src={product.image.src} alt={product.image.alt} fill priority sizes="(max-width: 768px) 100vw, 50vw" className="object-cover" /></div>
          <div className="flex flex-col p-6 sm:p-10">
            <p className="font-mono text-xs uppercase tracking-[.16em] text-emerald-700">{product.vendor} / {product.id}</p>
            <h1 className="mt-4 text-balance text-4xl font-black leading-none sm:text-6xl">{product.name}</h1>
            <p className="mt-6 text-lg leading-8 text-zinc-600">{product.description}</p>
            <dl className="mt-8 grid grid-cols-2 gap-px bg-zinc-200 border border-zinc-200">
              <div className="bg-white p-4"><dt className="text-xs uppercase text-zinc-500">Price</dt><dd className="mt-1 text-2xl font-black">{formatCurrency(product.price)}</dd></div>
              <div className="bg-white p-4"><dt className="text-xs uppercase text-zinc-500">Available</dt><dd className="mt-1 text-2xl font-black">{product.inventory}</dd></div>
            </dl>
            <div className="mt-auto grid gap-3 pt-8 text-sm text-zinc-600"><p className="flex items-center gap-2"><Database aria-hidden className="size-5 text-emerald-700" /> Price and stock originate in PostgreSQL.</p><p className="flex items-center gap-2"><PackageCheck aria-hidden className="size-5 text-emerald-700" /> Add this SKU from the catalog to create an idempotent order.</p></div>
          </div>
        </article>
      </div>
    </main>
  );
}
