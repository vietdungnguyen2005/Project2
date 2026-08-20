"use client";

import { CheckCircle2, ReceiptText, ShoppingCart } from "lucide-react";
import type { FormEvent } from "react";
import { formatCurrency } from "@/lib/commerce";
import type { CartLine, CartTotals, CheckoutForm, Order, Product } from "@/types/commerce";

export type CheckoutCartItem = {
  line: CartLine;
  product: Product;
};

type CheckoutPanelProps = {
  cartItems: CheckoutCartItem[];
  checkout: CheckoutForm;
  checkoutError: string;
  isSubmitting: boolean;
  order: Order | null;
  totalItems: number;
  totals: CartTotals;
  onCheckoutChange: (checkout: CheckoutForm) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
};

export function CheckoutPanel({
  cartItems,
  checkout,
  checkoutError,
  isSubmitting,
  order,
  totalItems,
  totals,
  onCheckoutChange,
  onSubmit,
}: CheckoutPanelProps) {
  return (
    <aside id="checkout" className="lg:sticky lg:top-4 lg:self-start">
      <div className="rounded-lg border border-zinc-200 bg-white p-4 shadow-sm">
        <div className="flex items-center justify-between gap-3">
          <div>
            <p className="text-sm font-bold uppercase tracking-[0.14em] text-emerald-700">
              Checkout
            </p>
            <h2 className="text-2xl font-black text-zinc-950">Order summary</h2>
          </div>
          <div
            aria-label={`${totalItems} items in cart`}
            className="inline-flex h-10 min-w-14 items-center justify-center gap-2 rounded-md bg-zinc-950 px-3 text-sm font-bold text-white"
          >
            <ShoppingCart aria-hidden className="size-4" />
            {totalItems}
          </div>
        </div>

        <div className="mt-4 grid gap-3">
          {cartItems.length === 0 ? (
            <p className="rounded-md bg-zinc-50 p-3 text-sm text-zinc-600">
              Add products to start a checkout-ready order.
            </p>
          ) : (
            cartItems.map(({ line, product }) => (
              <div
                key={line.productId}
                className="grid grid-cols-[1fr_auto] gap-3 border-b border-zinc-100 pb-3"
              >
                <div>
                  <p className="text-sm font-bold text-zinc-950">{product.name}</p>
                  <p className="text-xs text-zinc-500">
                    {line.quantity} x {formatCurrency(product.price)}
                  </p>
                </div>
                <p className="text-sm font-bold text-zinc-950">
                  {formatCurrency(product.price * line.quantity)}
                </p>
              </div>
            ))
          )}
        </div>

        <dl className="mt-4 grid gap-2 text-sm">
          <div className="flex justify-between">
            <dt className="text-zinc-500">Subtotal</dt>
            <dd className="font-bold text-zinc-950">{formatCurrency(totals.subtotal)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-zinc-500">Shipping</dt>
            <dd className="font-bold text-zinc-950">{formatCurrency(totals.shipping)}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-zinc-500">Estimated tax</dt>
            <dd className="font-bold text-zinc-950">{formatCurrency(totals.tax)}</dd>
          </div>
          <div className="flex justify-between border-t border-zinc-200 pt-3 text-base">
            <dt className="font-black text-zinc-950">Total</dt>
            <dd className="font-black text-zinc-950">{formatCurrency(totals.grandTotal)}</dd>
          </div>
        </dl>

        <form className="mt-5 grid gap-3" onSubmit={onSubmit}>
          <label className="grid gap-1 text-sm font-semibold text-zinc-700">
            Full name
            <input
              required
              minLength={2}
              maxLength={80}
              value={checkout.name}
              onChange={(event) => onCheckoutChange({ ...checkout, name: event.target.value })}
              className="h-10 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
            />
          </label>
          <label className="grid gap-1 text-sm font-semibold text-zinc-700">
            Email
            <input
              required
              type="email"
              maxLength={254}
              value={checkout.email}
              onChange={(event) => onCheckoutChange({ ...checkout, email: event.target.value })}
              className="h-10 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
            />
          </label>
          <label className="grid gap-1 text-sm font-semibold text-zinc-700">
            Postal code
            <input
              required
              pattern="[0-9]{3}-?[0-9]{4}"
              placeholder="100-0001"
              value={checkout.postalCode}
              onChange={(event) => onCheckoutChange({ ...checkout, postalCode: event.target.value })}
              className="h-10 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
            />
          </label>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
            <label className="grid min-w-0 gap-1 text-sm font-semibold text-zinc-700">
              Prefecture
              <input
                required
                maxLength={40}
                value={checkout.prefecture}
                onChange={(event) => onCheckoutChange({ ...checkout, prefecture: event.target.value })}
                className="h-10 w-full min-w-0 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
              />
            </label>
            <label className="grid min-w-0 gap-1 text-sm font-semibold text-zinc-700">
              City
              <input
                required
                minLength={2}
                maxLength={80}
                value={checkout.city}
                onChange={(event) => onCheckoutChange({ ...checkout, city: event.target.value })}
                className="h-10 w-full min-w-0 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
              />
            </label>
          </div>
          <label className="grid gap-1 text-sm font-semibold text-zinc-700">
            Address line
            <input
              required
              minLength={5}
              maxLength={160}
              value={checkout.addressLine}
              onChange={(event) => onCheckoutChange({ ...checkout, addressLine: event.target.value })}
              className="h-10 rounded-md border border-zinc-200 px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
            />
          </label>
          <label className="grid gap-1 text-sm font-semibold text-zinc-700">
            Payment
            <select
              value={checkout.paymentMethod}
              onChange={(event) =>
                onCheckoutChange({
                  ...checkout,
                  paymentMethod: event.target.value as CheckoutForm["paymentMethod"],
                })
              }
              className="h-10 rounded-md border border-zinc-200 bg-white px-3 text-zinc-950 outline-none transition focus:border-emerald-600 focus:ring-2 focus:ring-emerald-100"
            >
              <option value="invoice">Invoice before dispatch</option>
              <option value="cod">Pay on delivery</option>
            </select>
          </label>
          <label className="flex gap-3 rounded-md border border-zinc-200 bg-zinc-50 p-3 text-sm text-zinc-700">
            <input
              required
              type="checkbox"
              checked={checkout.privacyAccepted}
              onChange={(event) =>
                onCheckoutChange({ ...checkout, privacyAccepted: event.target.checked })
              }
              className="mt-1 size-4 shrink-0 accent-emerald-700"
            />
            <span>
              I agree that V-Market can use these order details to create and fulfill this order.
            </span>
          </label>
          <button
            type="submit"
            disabled={totals.itemCount === 0 || isSubmitting}
            className="inline-flex h-11 items-center justify-center gap-2 rounded-md bg-emerald-700 px-4 text-sm font-black text-white transition hover:bg-zinc-950 focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-zinc-300"
          >
            <ReceiptText aria-hidden className="size-4" />
            {isSubmitting ? "Placing order..." : "Place order"}
          </button>
        </form>

        {checkoutError ? (
          <p aria-live="polite" className="mt-3 text-sm font-semibold text-red-700">
            {checkoutError}
          </p>
        ) : null}

        {order ? (
          <div
            aria-live="polite"
            className="mt-4 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900"
          >
            <div className="flex items-center gap-2 font-black">
              <CheckCircle2 aria-hidden className="size-4" />
              Order {order.orderNumber} confirmed
            </div>
            <p className="mt-1">
              Order recorded durably. Keep this bearer token private:
            </p>
            <code className="mt-2 block break-all rounded bg-white/70 p-2 text-xs">{order.trackingToken}</code>
            <p className="mt-2 inline-flex items-center gap-2 font-semibold">
              <ReceiptText aria-hidden className="size-4" />
              Payment pending. Total {formatCurrency(order.grandTotalMinor)}.
            </p>
            <a href={`/track?order=${encodeURIComponent(order.orderNumber)}&token=${encodeURIComponent(order.trackingToken)}`} className="mt-3 inline-flex min-h-10 items-center font-black underline decoration-2 underline-offset-4">Open tracking timeline</a>
          </div>
        ) : null}
      </div>
    </aside>
  );
}
