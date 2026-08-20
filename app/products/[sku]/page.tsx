import type { Metadata } from "next";
import { ProductDetail } from "@/components/product-detail";

export const metadata: Metadata = { title: "Product detail" };

export default async function ProductPage({ params }: { params: Promise<{ sku: string }> }) {
  const { sku } = await params;
  return <ProductDetail sku={sku} />;
}
