export type Product = {
  id: string;
  vendor: string;
  name: string;
  category: "Apparel" | "Home" | "Office" | "Travel";
  description: string;
  price: number;
  inventory: number;
  rating: number;
  reviewCount: number;
  badge: string;
  shippingSla: string;
  image: {
    src: string;
    alt: string;
    width: number;
    height: number;
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

export type DeliveryWindow = "standard" | "express";

export type PaymentMethod = "invoice" | "cod";

export type CheckoutForm = {
  name: string;
  email: string;
  address: string;
  city: string;
  deliveryWindow: DeliveryWindow;
  paymentMethod: PaymentMethod;
  privacyAccepted: boolean;
};

export type Order = {
  id: string;
  cart: Cart;
  totals: CartTotals;
  customer: CheckoutForm;
  createdAt: string;
  privacyAcceptedAt: string;
  status: "confirmed";
  paymentStatus: "pending";
  fulfillmentStatus: "received";
};
