export type TrackedOrder = {
  orderNumber: string;
  paymentStatus: string;
  fulfillmentStatus: string;
  grandTotalMinor: number;
  currency: "JPY";
  createdAt: string;
  items: Array<{ sku: string; name: string; unitPriceMinor: number; quantity: number }>;
};

export async function trackOrder(orderNumber: string, trackingToken: string): Promise<TrackedOrder> {
  const response = await fetch(
    `/api/orders/${encodeURIComponent(orderNumber.trim())}?trackingToken=${encodeURIComponent(trackingToken.trim())}`,
    { cache: "no-store" },
  );
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(payload?.message ?? "Order could not be found.");
  }
  return (await response.json()) as TrackedOrder;
}
