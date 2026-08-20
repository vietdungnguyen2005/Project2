import { describe, expect, it } from "vitest";
import { mapCatalogProduct } from "@/lib/catalog-api";

describe("catalog API contract", () => {
  it("maps canonical JPY inventory without inventing reviews or stock", () => {
    expect(
      mapCatalogProduct({
        sku: "VM-001",
        vendorName: "Nami Studio",
        name: "AeroKnit travel jacket",
        category: "APPAREL",
        description: "Travel jacket",
        priceMinor: 11_000,
        currency: "JPY",
        imagePath: "/products/aeroknit-travel-jacket.jpg",
        imageAlt: "Travel jacket",
        availableQuantity: 32,
      }),
    ).toEqual({
      id: "VM-001",
      vendor: "Nami Studio",
      name: "AeroKnit travel jacket",
      category: "Apparel",
      description: "Travel jacket",
      price: 11_000,
      inventory: 32,
      currency: "JPY",
      image: {
        src: "/products/aeroknit-travel-jacket.jpg",
        alt: "Travel jacket",
      },
    });
  });
});
