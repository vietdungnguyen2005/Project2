import { z } from "zod";

export const checkoutStorageKey = "vmarket:checkout-operation:v1";
export const guestStorageKey = "vmarket:checkout-session:v1";
export const hexDigestSchema = z.string().regex(/^[0-9a-f]{64}$/);

export const pendingCheckoutSchema = z.object({
  version: z.literal(1),
  key: z.string().uuid(),
  payloadHash: hexDigestSchema,
  capabilityHash: hexDigestSchema,
  createdAt: z.string().datetime({ offset: true }),
  phase: z.enum(["unresolved", "confirmed", "closed"]),
}).strict();

export const checkoutOrderSchema = z.object({
  orderNumber: z.string().min(1).max(40),
  trackingToken: hexDigestSchema,
  paymentStatus: z.literal("PENDING"),
  fulfillmentStatus: z.enum(["RECEIVED", "PROCESSING", "SHIPPED", "DELIVERED"]),
  subtotalMinor: z.number().int().nonnegative(), shippingMinor: z.number().int().nonnegative(),
  taxMinor: z.number().int().nonnegative(), grandTotalMinor: z.number().int().nonnegative(),
  currency: z.literal("JPY"), createdAt: z.string().datetime({ offset: true }),
}).strict();

const customerSchema = z.object({
  name: z.string().trim().min(1).max(80), email: z.string().trim().email().max(254),
  postalCode: z.string().trim().regex(/^[0-9]{3}-?[0-9]{4}$/),
  prefecture: z.string().trim().min(1).max(40), city: z.string().trim().min(1).max(80),
  addressLine: z.string().trim().min(1).max(160), paymentMethod: z.enum(["invoice", "cod"]),
  privacyAccepted: z.literal(true),
}).strict();
const cartSchema = z.object({
  lines: z.array(z.object({ productId: z.string().trim().min(1).max(64), quantity: z.number().int().min(1).max(99) }).strict()).min(1).max(100),
  updatedAt: z.string(),
}).strict();

export const checkoutCommandSchema = z.discriminatedUnion("action", [
  z.object({ action: z.literal("restore") }).strict(),
  z.object({ action: z.literal("submit"), cart: cartSchema, customer: customerSchema }).strict(),
  z.object({ action: z.literal("recover") }).strict(),
  z.object({ action: z.literal("close") }).strict(),
  z.object({ action: z.literal("acknowledge") }).strict(),
]);
export const checkoutViewSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("idle") }),
  z.object({ status: z.literal("blocked") }),
  z.object({ status: z.literal("unresolved"), operation: pendingCheckoutSchema }),
  z.object({ status: z.literal("confirmed"), operation: pendingCheckoutSchema, order: checkoutOrderSchema.optional() }),
  z.object({ status: z.literal("closed"), operation: pendingCheckoutSchema }),
]);
export const checkoutErrorSchema = z.object({
  code: z.enum(["INVALID_INPUT", "INVALID_STORAGE", "STORAGE_UNAVAILABLE", "IDENTITY_CHANGED", "PAYLOAD_CHANGED", "OUTCOME_UNKNOWN", "NOT_OBSERVED", "BLOCKED", "BUSY", "UNEXPECTED_RESPONSE"]),
  message: z.string(), recoverable: z.boolean(),
});
export const checkoutResultSchema = z.discriminatedUnion("success", [
  z.object({ success: z.literal(true), state: checkoutViewSchema }),
  z.object({ success: z.literal(false), state: checkoutViewSchema, error: checkoutErrorSchema }),
]);
export type PendingCheckout = z.infer<typeof pendingCheckoutSchema>;
export type CheckoutView = z.infer<typeof checkoutViewSchema>;
export type CheckoutResult = z.infer<typeof checkoutResultSchema>;
export type CheckoutError = z.infer<typeof checkoutErrorSchema>;
export type CheckoutCommand = z.infer<typeof checkoutCommandSchema>;
export interface CheckoutSpec { execute(input: unknown): Promise<CheckoutResult>; }
export type CheckoutDependencies = {
  storage: Pick<Storage, "getItem" | "setItem" | "removeItem">;
  request: (url: string, init: RequestInit) => Promise<Response>;
  timeoutMs?: number;
};
