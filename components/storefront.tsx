"use client";

import { useQuery } from "@tanstack/react-query";
import { type FormEvent, useMemo, useState } from "react";
import { CatalogControls } from "@/components/catalog-controls";
import { CatalogGrid } from "@/components/catalog-grid";
import { CheckoutPanel, type CheckoutCartItem } from "@/components/checkout-panel";
import { CheckoutRecovery, type RecoveryAction } from "@/components/checkout-recovery";
import { HeroSection } from "@/components/hero-section";
import { loadCatalog } from "@/lib/catalog-api";
import { useCart } from "@/hooks/use-cart";
import { useCheckout } from "@/hooks/use-checkout";
import {
  calculateCartTotals,
  filterProducts,
  type CategoryFilter,
  type SortMode,
} from "@/lib/commerce";
import type { CheckoutForm, Order } from "@/types/commerce";

const initialCheckout: CheckoutForm = {
  name: "",
  email: "",
  postalCode: "",
  prefecture: "Tokyo",
  city: "",
  addressLine: "",
  paymentMethod: "invoice",
  privacyAccepted: false,
};

export function Storefront() {
  const catalog = useQuery({ queryKey: ["catalog"], queryFn: ({ signal }) => loadCatalog(signal) });
  const products = catalog.data ?? [];
  const { cart, totalItems, getQuantity, setQuantity, clearCart, isUpdating } = useCart();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<CategoryFilter>("All");
  const [sortMode, setSortMode] = useState<SortMode>("featured");
  const [checkout, setCheckout] = useState<CheckoutForm>(initialCheckout);
  const [order, setOrder] = useState<Order | null>(null);
  const flow = useCheckout();
  const checkoutLocked = !flow.ready || flow.busy || flow.state.status !== "idle";

  const filteredProducts = filterProducts(products, query, category, sortMode);
  const totals = calculateCartTotals(cart, products);
  const cartItems = useMemo<CheckoutCartItem[]>(
    () =>
      cart.lines.flatMap((line) => {
        const product = products.find((item) => item.id === line.productId);
        return product ? [{ line, product }] : [];
      }),
    [cart.lines],
  );

  const handleCheckout = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (totals.itemCount === 0 || checkoutLocked || isUpdating) {
      return;
    }

    const result = await flow.run({ action: "submit", cart, customer: checkout });
    if (result?.success && result.state.status === "confirmed" && result.state.order) {
      setOrder(result.state.order);
      setCheckout(initialCheckout);
      clearCart();
    }
  };

  const handleRecovery = async (action: RecoveryAction) => {
    const previous = flow.state.status;
    const result = await flow.run({ action });
    if (!result?.success) return;
    if (result.state.status === "confirmed" && result.state.order) setOrder(result.state.order);
    if (action === "acknowledge") {
      setOrder(null);
      if (previous === "confirmed") { clearCart(); setCheckout(initialCheckout); }
    }
  };

  return (
    <main>
      <HeroSection heroProduct={products[0]} totalItems={totalItems} />

      <section id="catalog" className="bg-white py-10 sm:py-14">
        <div className="mx-auto grid max-w-7xl gap-8 px-4 sm:px-6 lg:grid-cols-[1fr_24rem] lg:px-8">
          <div>
            {catalog.isError ? (
              <div role="alert" className="mb-6 border-l-4 border-rose-600 bg-rose-50 p-4 text-sm text-rose-900">
                Catalog connection failed. The storefront will not invent stock while the backend is unavailable.
              </div>
            ) : null}
            <CatalogControls
              category={category}
              isUpdating={isUpdating}
              productCount={filteredProducts.length}
              query={query}
              sortMode={sortMode}
              onCategoryChange={setCategory}
              onQueryChange={setQuery}
              onSortModeChange={setSortMode}
            />
            <CatalogGrid
              disabled={checkoutLocked}
              products={filteredProducts}
              getQuantity={getQuantity}
              onQuantityChange={setQuantity}
            />
          </div>

          <CheckoutPanel
            cartItems={cartItems}
            checkout={checkout}
            checkoutError={flow.error}
            isSubmitting={flow.busy}
            checkoutLocked={checkoutLocked || isUpdating}
            recoveryControls={<CheckoutRecovery state={flow.state} busy={flow.busy} onAction={action => { void handleRecovery(action); }} />}
            order={order}
            totalItems={totalItems}
            totals={totals}
            onCheckoutChange={setCheckout}
            onSubmit={handleCheckout}
          />
        </div>
      </section>
    </main>
  );
}
