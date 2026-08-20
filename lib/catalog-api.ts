import type { Product } from "@/types/commerce";

export type CatalogProductResponse = {
  sku: string;
  vendorName: string;
  name: string;
  category: "APPAREL" | "HOME" | "OFFICE" | "TRAVEL";
  description: string;
  priceMinor: number;
  currency: "JPY";
  imagePath: string;
  imageAlt: string;
  availableQuantity: number;
};

const categoryLabels: Record<CatalogProductResponse["category"], Product["category"]> = {
  APPAREL: "Apparel",
  HOME: "Home",
  OFFICE: "Office",
  TRAVEL: "Travel",
};

export function mapCatalogProduct(product: CatalogProductResponse): Product {
  return {
    id: product.sku,
    vendor: product.vendorName,
    name: product.name,
    category: categoryLabels[product.category],
    description: product.description,
    price: product.priceMinor,
    inventory: product.availableQuantity,
    currency: product.currency,
    image: {
      src: product.imagePath,
      alt: product.imageAlt,
    },
  };
}

export async function loadCatalog(signal?: AbortSignal): Promise<Product[]> {
  const response = await fetch("/api/catalog/products", {
    cache: "no-store",
    signal,
  });
  if (!response.ok) {
    throw new Error("Catalog is temporarily unavailable.");
  }
  const payload = (await response.json()) as CatalogProductResponse[];
  return payload.map(mapCatalogProduct);
}
