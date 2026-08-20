import { proxyToBackend } from "@/lib/backend-proxy";

export async function GET(
  request: Request,
  context: { params: Promise<{ orderNumber: string }> },
) {
  const { orderNumber } = await context.params;
  const trackingToken = new URL(request.url).searchParams.get("trackingToken") ?? "";
  return proxyToBackend(
    request,
    `/api/orders/${encodeURIComponent(orderNumber)}?trackingToken=${encodeURIComponent(trackingToken)}`,
  );
}
