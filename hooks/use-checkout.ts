"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { CheckoutHandler } from "@/lib/checkout/handler";
import type { CheckoutResult, CheckoutView } from "@/lib/checkout/spec";

export function useCheckout() {
  // Closures defer browser storage/fetch access until after hydration or an explicit action.
  const [handler] = useState(() => new CheckoutHandler({ storage: {
    getItem: key => window.sessionStorage.getItem(key),
    setItem: (key, value) => window.sessionStorage.setItem(key, value),
    removeItem: key => window.sessionStorage.removeItem(key),
  }, request: (url, init) => fetch(url, init) }));
  const [state, setState] = useState<CheckoutView>({ status: "blocked" });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);
  const [ready, setReady] = useState(false);
  const mounted = useRef(false);
  const inFlight = useRef(false);
  const initialization = useRef<Promise<CheckoutResult> | null>(null);

  const apply = useCallback((result: CheckoutResult) => {
    setState(result.state);
    setError(result.success ? "" : result.error.message);
  }, []);

  useEffect(() => {
    mounted.current = true;
    // Strict Mode effect re-entry shares the same restore, not a competing handler command.
    initialization.current ??= handler.execute({ action: "restore" });
    void initialization.current.then(result => {
      if (mounted.current) { apply(result); setBusy(false); setReady(true); }
    });
    return () => { mounted.current = false; };
  }, [handler, apply]);

  const run = useCallback(async (input: unknown): Promise<CheckoutResult | undefined> => {
    if (!ready || inFlight.current) return undefined;
    inFlight.current = true;
    setBusy(true);
    setError("");
    try {
      const result = await handler.execute(input);
      if (mounted.current) apply(result);
      return result;
    } finally {
      inFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  }, [handler, ready, apply]);
  return { state, error, busy, ready, run };
}
