import { getCloudflareContext } from "@opennextjs/cloudflare";
import { NextResponse } from "next/server";
import { products } from "@/data/products";
import { buildOrderFromRequest } from "@/lib/order-validation";
import type { Order } from "@/types/commerce";

type OrderBucket = {
  put: (
    key: string,
    value: string,
    options?: { httpMetadata?: { contentType?: string } },
  ) => Promise<unknown>;
};

type CloudflareOrderEnv = {
  V_MARKET_ORDERS?: OrderBucket;
};

async function persistOrder(order: Order): Promise<"r2" | "local-fallback"> {
  try {
    const context = getCloudflareContext();
    const env = context.env as CloudflareOrderEnv;

    if (!env.V_MARKET_ORDERS) {
      return "local-fallback";
    }

    await env.V_MARKET_ORDERS.put(`orders/${order.id}.json`, JSON.stringify(order), {
      httpMetadata: {
        contentType: "application/json",
      },
    });

    return "r2";
  } catch {
    return "local-fallback";
  }
}

export async function POST(request: Request) {
  const payload = await request.json().catch(() => null);
  const result = buildOrderFromRequest(payload, products);

  if (!result.ok) {
    return NextResponse.json(
      { message: "Invalid order", errors: result.errors },
      {
        status: 400,
        headers: {
          "Cache-Control": "no-store",
        },
      },
    );
  }

  const order = result.order;
  const storage = await persistOrder(order);

  return NextResponse.json(
    { ...order, storage },
    {
      headers: {
        "Cache-Control": "no-store",
      },
    },
  );
}
