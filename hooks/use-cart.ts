"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef } from "react";
import { updateCartQuantity } from "@/lib/cart-api";
import { readStoredCart, writeStoredCart } from "@/lib/cart-storage";
import {
  applyQuantity,
  CartMutationCoordinator,
  emptyCart,
  type QuantityPayload,
} from "@/lib/cart-race";
import type { Cart } from "@/types/commerce";

const cartKey = ["cart"] as const;

export function useCart() {
  const queryClient = useQueryClient();
  const coordinatorRef = useRef(new CartMutationCoordinator());
  const hasHydratedRef = useRef(false);

  const cartQuery = useQuery({
    queryKey: cartKey,
    queryFn: async () => emptyCart(),
    initialData: emptyCart,
  });

  const mutation = useMutation({
    mutationFn: async (payload: QuantityPayload) => {
      const currentCart = queryClient.getQueryData<Cart>(cartKey) ?? emptyCart();
      return updateCartQuantity(
        currentCart,
        payload.productId,
        payload.quantity,
        payload.signal,
      );
    },
    onMutate: async (payload) => {
      await queryClient.cancelQueries({ queryKey: cartKey });
      const previousCart = queryClient.getQueryData<Cart>(cartKey) ?? emptyCart();

      queryClient.setQueryData<Cart>(
        cartKey,
        applyQuantity(previousCart, payload.productId, payload.quantity),
      );

      return { previousCart };
    },
    onError: (error, payload, context) => {
      if (error instanceof DOMException && error.name === "AbortError") {
        return;
      }

      if (coordinatorRef.current.isCurrent(payload.productId, payload.sequence)) {
        queryClient.setQueryData(cartKey, context?.previousCart ?? emptyCart());
      }
    },
    onSuccess: (serverCart, payload) => {
      if (coordinatorRef.current.isCurrent(payload.productId, payload.sequence)) {
        queryClient.setQueryData(cartKey, serverCart);
      }
    },
    onSettled: (_data, _error, payload) => {
      if (payload) {
        coordinatorRef.current.settle(payload.productId, payload.sequence);
      }
    },
  });

  const cart = cartQuery.data;
  const linesKey = JSON.stringify(cart.lines);

  useEffect(() => {
    queryClient.setQueryData<Cart>(cartKey, readStoredCart(window.localStorage));
    queueMicrotask(() => {
      hasHydratedRef.current = true;
    });
  }, [queryClient]);

  useEffect(() => {
    if (hasHydratedRef.current) {
      writeStoredCart(window.localStorage, cart);
    }
  }, [cart, linesKey]);

  const totalItems = useMemo(
    () => cart.lines.reduce((total, line) => total + line.quantity, 0),
    [cart.lines],
  );

  const setQuantity = (productId: string, quantity: number) => {
    const payload = coordinatorRef.current.withQuantity(
      coordinatorRef.current.prepare(productId),
      quantity,
    );

    mutation.mutate(payload);
  };

  const getQuantity = (productId: string) =>
    cart.lines.find((line) => line.productId === productId)?.quantity ?? 0;

  const clearCart = () => {
    queryClient.setQueryData<Cart>(cartKey, emptyCart());
  };

  return {
    cart,
    totalItems,
    isUpdating: mutation.isPending,
    setQuantity,
    getQuantity,
    clearCart,
  };
}
