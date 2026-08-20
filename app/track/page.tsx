import type { Metadata } from "next";
import { OrderTracker } from "@/components/order-tracker";

export const metadata: Metadata = {
  title: "Track Order",
  description: "Privacy-preserving V-Market fulfillment tracking.",
};

type TrackOrderPageProps = {
  searchParams: Promise<{ order?: string; token?: string }>;
};

export default async function TrackOrderPage({ searchParams }: TrackOrderPageProps) {
  const { order, token } = await searchParams;
  return <OrderTracker initialOrderNumber={order} initialTrackingToken={token} />;
}
