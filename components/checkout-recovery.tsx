"use client";

import type { CheckoutView } from "@/lib/checkout/spec";
export type RecoveryAction = "recover" | "close" | "acknowledge" | "restore";
export type CheckoutRecoveryProps = { state: CheckoutView; busy: boolean; onAction: (action: RecoveryAction) => void };

export function CheckoutRecovery({ state, busy, onAction }: CheckoutRecoveryProps) {
  if (state.status === "idle") return null;
  const terminal = state.status === "confirmed" || state.status === "closed";
  const title = state.status === "confirmed" ? "Saved checkout confirmed" : state.status === "closed" ? "Unused checkout closed"
    : state.status === "blocked" ? "Checkout needs attention" : "Checkout result not confirmed";
  const button = "min-h-11 rounded-md border border-current px-3 py-2 text-sm font-bold focus:outline-none focus:ring-2 focus:ring-emerald-600 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50";
  return <section aria-label="Checkout recovery" aria-live="polite" aria-busy={busy}
    className={`mt-4 min-w-0 rounded-md border border-l-4 p-3 text-sm ${terminal ? "border-emerald-700 bg-emerald-50 text-emerald-950" : "border-amber-700 bg-amber-50 text-amber-950"}`}>
    <h3 className="font-black">{title}</h3>
    <p className="mt-2 leading-6">{state.status === "confirmed"
      ? "Save the tracking link before starting another checkout. Starting again clears this tab's current cart."
      : state.status === "closed" ? "The old unused key is closed. Late requests cannot create an order with it. Continue to edit your cart and details."
      : state.status === "blocked" ? "Restore tab storage or request operator review. A replacement operation will not be created automatically."
      : "Keep the saved key while the result is unknown. Closing only succeeds if no order exists; it does not cancel a confirmed order or refund a payment."}</p>
    <div className="mt-3 flex flex-wrap gap-2">
      {state.status === "blocked" ? <button type="button" disabled={busy} className={button} onClick={() => onAction("restore")}>Retry local recovery</button> : null}
      {state.status === "unresolved" || state.status === "confirmed" ? <button type="button" disabled={busy} className={button} onClick={() => onAction("recover")}>Check checkout result</button> : null}
      {state.status === "unresolved" ? <button type="button" disabled={busy} className={button} onClick={() => onAction("close")}>Close unused checkout</button> : null}
      {terminal ? <button type="button" disabled={busy} className={button} onClick={() => onAction("acknowledge")}>{state.status === "confirmed" ? "Start a new checkout" : "Edit checkout and continue"}</button> : null}
    </div>
  </section>;
}
