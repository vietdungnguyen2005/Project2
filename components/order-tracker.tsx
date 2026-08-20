"use client";

import { ArrowLeft, Box, MapPinCheck, Search } from "lucide-react";
import { type FormEvent, useState } from "react";
import { formatCurrency } from "@/lib/commerce";
import { trackOrder, type TrackedOrder } from "@/lib/tracking-api";

const fulfillmentSteps = ["RECEIVED", "PROCESSING", "SHIPPED", "DELIVERED"] as const;

type OrderTrackerProps = {
  initialOrderNumber?: string;
  initialTrackingToken?: string;
};

export function OrderTracker({ initialOrderNumber = "", initialTrackingToken = "" }: OrderTrackerProps) {
  const [orderNumber, setOrderNumber] = useState(initialOrderNumber);
  const [trackingToken, setTrackingToken] = useState(initialTrackingToken);
  const [order, setOrder] = useState<TrackedOrder | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      setOrder(await trackOrder(orderNumber, trackingToken));
    } catch (caught) {
      setOrder(null);
      setError(caught instanceof Error ? caught.message : "Order lookup failed.");
    } finally {
      setLoading(false);
    }
  };

  const activeIndex = order ? fulfillmentSteps.indexOf(order.fulfillmentStatus as (typeof fulfillmentSteps)[number]) : -1;

  return (
    <main className="min-h-screen bg-[#f7f5ef] text-zinc-950">
      <div className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:px-8">
        <a href="/" className="inline-flex min-h-11 items-center gap-2 text-sm font-black uppercase tracking-[0.12em] text-zinc-600 hover:text-zinc-950">
          <ArrowLeft aria-hidden className="size-4" /> Storefront
        </a>
        <div className="mt-10 grid gap-10 lg:grid-cols-[.75fr_1.25fr]">
          <section>
            <p className="text-sm font-black uppercase tracking-[0.18em] text-emerald-700">配送状況 / Order tracking</p>
            <h1 className="mt-4 text-balance text-5xl font-black leading-[.95] sm:text-6xl">Trace the durable order.</h1>
            <p className="mt-5 max-w-lg text-lg leading-8 text-zinc-600">
              The lookup requires both the public order number and its opaque bearer token. Customer PII is never returned.
            </p>
            <form onSubmit={submit} className="mt-8 grid gap-4 border border-zinc-300 bg-white p-5 shadow-[8px_8px_0_#18181b]">
              <label className="grid gap-2 text-sm font-bold">
                Order number
                <input required value={orderNumber} onChange={(event) => setOrderNumber(event.target.value)} placeholder="VM-…" className="h-12 border border-zinc-300 px-3 font-mono outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
              </label>
              <label className="grid gap-2 text-sm font-bold">
                Tracking token
                <input required value={trackingToken} onChange={(event) => setTrackingToken(event.target.value)} className="h-12 border border-zinc-300 px-3 font-mono text-xs outline-none focus:border-emerald-700 focus:ring-2 focus:ring-emerald-100" />
              </label>
              <button disabled={loading} className="inline-flex min-h-12 items-center justify-center gap-2 bg-zinc-950 px-5 text-sm font-black uppercase tracking-wider text-white hover:bg-emerald-700 disabled:opacity-50">
                <Search aria-hidden className="size-4" /> {loading ? "Tracing…" : "Track order"}
              </button>
              {error ? <p role="alert" className="text-sm font-semibold text-rose-700">{error}</p> : null}
            </form>
          </section>

          <section aria-live="polite" className="min-h-[32rem] border border-zinc-300 bg-white p-6 sm:p-8">
            {order ? (
              <div>
                <div className="flex flex-wrap items-start justify-between gap-4 border-b border-zinc-200 pb-6">
                  <div>
                    <p className="font-mono text-xs uppercase tracking-widest text-emerald-700">Verified ledger entry</p>
                    <h2 className="mt-2 text-3xl font-black">{order.orderNumber}</h2>
                  </div>
                  <div className="text-right">
                    <p className="text-sm text-zinc-500">Order total</p>
                    <p className="text-2xl font-black">{formatCurrency(order.grandTotalMinor)}</p>
                  </div>
                </div>
                <ol className="mt-8 grid gap-3 sm:grid-cols-4">
                  {fulfillmentSteps.map((step, index) => (
                    <li key={step} className={`border-t-4 p-3 ${index <= activeIndex ? "border-emerald-600 bg-emerald-50" : "border-zinc-200 bg-zinc-50"}`}>
                      <p className="font-mono text-[10px] text-zinc-500">0{index + 1}</p>
                      <p className="mt-1 text-xs font-black">{step}</p>
                    </li>
                  ))}
                </ol>
                <div className="mt-8 grid gap-3">
                  {order.items.map((item) => (
                    <article key={item.sku} className="grid grid-cols-[1fr_auto] gap-4 border-b border-zinc-100 py-3">
                      <div className="flex gap-3"><Box aria-hidden className="mt-1 size-5 text-emerald-700" /><div><h3 className="font-bold">{item.name}</h3><p className="font-mono text-xs text-zinc-500">{item.sku} · qty {item.quantity}</p></div></div>
                      <p className="font-bold">{formatCurrency(item.unitPriceMinor * item.quantity)}</p>
                    </article>
                  ))}
                </div>
                <p className="mt-8 inline-flex items-center gap-2 text-sm font-semibold text-zinc-600"><MapPinCheck aria-hidden className="size-5 text-emerald-700" /> Payment {order.paymentStatus.toLowerCase()} · customer details protected</p>
              </div>
            ) : (
              <div className="grid min-h-[28rem] place-items-center text-center text-zinc-400"><div><Box aria-hidden className="mx-auto size-12" /><p className="mt-4 font-semibold">A verified order timeline will appear here.</p></div></div>
            )}
          </section>
        </div>
      </div>
    </main>
  );
}
