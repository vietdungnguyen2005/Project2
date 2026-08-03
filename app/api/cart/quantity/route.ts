import { NextResponse } from "next/server";
import { applyQuantity } from "@/lib/cart-race";
import type { Cart } from "@/types/commerce";

type QuantityRequest = {
  cart: Cart;
  productId: string;
  quantity: number;
};

export async function POST(request: Request) {
  const payload = (await request.json()) as QuantityRequest;
  const latency = Number(request.headers.get("x-v-market-latency-ms") ?? 24);

  await new Promise((resolve, reject) => {
    const timeout = setTimeout(resolve, Math.min(400, Math.max(0, latency)));

    request.signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timeout);
        reject(request.signal.reason);
      },
      { once: true },
    );
  });

  return NextResponse.json(
    applyQuantity(payload.cart, payload.productId, payload.quantity),
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
