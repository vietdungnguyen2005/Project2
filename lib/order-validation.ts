import { createOrder } from "@/lib/commerce";
import type { Cart, CheckoutForm, DeliveryWindow, Order, PaymentMethod, Product } from "@/types/commerce";

const MAX_ORDER_QUANTITY = 99;
const MAX_FIELD_LENGTH = {
  name: 80,
  email: 254,
  address: 160,
  city: 80,
} as const;

const deliveryWindows = new Set<DeliveryWindow>(["standard", "express"]);
const paymentMethods = new Set<PaymentMethod>(["invoice", "cod"]);
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export type OrderValidationResult =
  | {
      ok: true;
      order: Order;
    }
  | {
      ok: false;
      errors: string[];
    };

type NormalizedPayload = {
  cart: Cart;
  customer: CheckoutForm;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function sanitizeText(value: unknown, maxLength: number): string {
  if (typeof value !== "string") {
    return "";
  }

  return value.replace(/\s+/g, " ").trim().slice(0, maxLength);
}

function normalizeCustomer(value: unknown): { customer?: CheckoutForm; errors: string[] } {
  const errors: string[] = [];

  if (!isRecord(value)) {
    return { errors: ["Customer details are required."] };
  }

  const name = sanitizeText(value.name, MAX_FIELD_LENGTH.name);
  const email = sanitizeText(value.email, MAX_FIELD_LENGTH.email).toLowerCase();
  const address = sanitizeText(value.address, MAX_FIELD_LENGTH.address);
  const city = sanitizeText(value.city, MAX_FIELD_LENGTH.city);
  const deliveryWindow = value.deliveryWindow;
  const paymentMethod = value.paymentMethod;
  const privacyAccepted = value.privacyAccepted === true;

  if (name.length < 2) {
    errors.push("Name must be at least 2 characters.");
  }

  if (!emailPattern.test(email)) {
    errors.push("A valid email address is required.");
  }

  if (address.length < 5) {
    errors.push("Address must be at least 5 characters.");
  }

  if (city.length < 2) {
    errors.push("City must be at least 2 characters.");
  }

  if (!deliveryWindows.has(deliveryWindow as DeliveryWindow)) {
    errors.push("Delivery window is not supported.");
  }

  if (!paymentMethods.has(paymentMethod as PaymentMethod)) {
    errors.push("Payment method is not supported.");
  }

  if (!privacyAccepted) {
    errors.push("Privacy acknowledgement is required.");
  }

  if (errors.length > 0) {
    return { errors };
  }

  return {
    customer: {
      name,
      email,
      address,
      city,
      deliveryWindow: deliveryWindow as DeliveryWindow,
      paymentMethod: paymentMethod as PaymentMethod,
      privacyAccepted,
    },
    errors,
  };
}

function normalizeCart(value: unknown, catalog: Product[]): { cart?: Cart; errors: string[] } {
  const errors: string[] = [];

  if (!isRecord(value) || !Array.isArray(value.lines)) {
    return { errors: ["Cart lines are required."] };
  }

  const productById = new Map(catalog.map((product) => [product.id, product]));
  const quantityByProductId = new Map<string, number>();

  value.lines.forEach((line, index) => {
    if (!isRecord(line)) {
      errors.push(`Cart line ${index + 1} is malformed.`);
      return;
    }

    const productId = typeof line.productId === "string" ? line.productId : "";
    const product = productById.get(productId);
    const quantity = line.quantity;

    if (!product) {
      errors.push(`Cart line ${index + 1} references an unknown product.`);
      return;
    }

    if (typeof quantity !== "number" || !Number.isFinite(quantity) || !Number.isInteger(quantity)) {
      errors.push(`Cart line ${index + 1} has an invalid quantity.`);
      return;
    }

    if (quantity < 1) {
      errors.push(`Cart line ${index + 1} quantity must be at least 1.`);
      return;
    }

    const nextQuantity = (quantityByProductId.get(productId) ?? 0) + quantity;
    const maximumQuantity = Math.min(product.inventory, MAX_ORDER_QUANTITY);

    if (nextQuantity > maximumQuantity) {
      errors.push(`${product.name} exceeds available inventory.`);
      return;
    }

    quantityByProductId.set(productId, nextQuantity);
  });

  if (quantityByProductId.size === 0) {
    errors.push("Cart must contain at least one valid item.");
  }

  if (errors.length > 0) {
    return { errors };
  }

  return {
    cart: {
      lines: [...quantityByProductId.entries()]
        .map(([productId, quantity]) => ({ productId, quantity }))
        .sort((a, b) => a.productId.localeCompare(b.productId)),
      updatedAt: new Date().toISOString(),
    },
    errors,
  };
}

export function normalizeOrderRequest(
  payload: unknown,
  catalog: Product[],
): { payload?: NormalizedPayload; errors: string[] } {
  if (!isRecord(payload)) {
    return { errors: ["Order payload must be an object."] };
  }

  const cartResult = normalizeCart(payload.cart, catalog);
  const customerResult = normalizeCustomer(payload.customer);
  const errors = [...cartResult.errors, ...customerResult.errors];

  if (errors.length > 0 || !cartResult.cart || !customerResult.customer) {
    return { errors };
  }

  return {
    payload: {
      cart: cartResult.cart,
      customer: customerResult.customer,
    },
    errors,
  };
}

export function buildOrderFromRequest(
  payload: unknown,
  catalog: Product[],
): OrderValidationResult {
  const normalized = normalizeOrderRequest(payload, catalog);

  if (!normalized.payload) {
    return {
      ok: false,
      errors: normalized.errors,
    };
  }

  return {
    ok: true,
    order: createOrder(normalized.payload.cart, catalog, normalized.payload.customer),
  };
}
