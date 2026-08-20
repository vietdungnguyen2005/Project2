import { proxyToBackend } from "@/lib/backend-proxy";

export const runtime = "nodejs";

type RouteContext = { params: Promise<{ orderNumber: string }> };

export async function PATCH(request: Request, context: RouteContext): Promise<Response> {
  const { orderNumber } = await context.params;
  return proxyToBackend(
    request,
    `/api/ops/orders/${encodeURIComponent(orderNumber)}/fulfillment`,
    { operations: true },
  );
}
