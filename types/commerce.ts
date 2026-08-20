export type Product = {
  id: string;
  vendor: string;
  name: string;
  category: "Apparel" | "Home" | "Office" | "Travel";
  description: string;
  price: number;
  inventory: number;
  currency: "JPY";
  image: {
    src: string;
    alt: string;
  };
};

export type CartLine = {
  productId: string;
  quantity: number;
};

export type Cart = {
  lines: CartLine[];
  updatedAt: string;
};

export type CartTotals = {
  subtotal: number;
  shipping: number;
  tax: number;
  grandTotal: number;
  itemCount: number;
};

export type PaymentMethod = "invoice" | "cod";

export type CheckoutForm = {
  name: string;
  email: string;
  postalCode: string;
  prefecture: string;
  city: string;
  addressLine: string;
  paymentMethod: PaymentMethod;
  privacyAccepted: boolean;
};

export type Order = {
  orderNumber: string;
  trackingToken: string;
  paymentStatus: "PENDING";
  fulfillmentStatus: "RECEIVED" | "PROCESSING" | "SHIPPED" | "DELIVERED";
  subtotalMinor: number;
  shippingMinor: number;
  taxMinor: number;
  grandTotalMinor: number;
  currency: "JPY";
  createdAt: string;
};
