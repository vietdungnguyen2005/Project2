import { checkoutCommandSchema, checkoutOrderSchema, checkoutStorageKey, guestStorageKey, hexDigestSchema, pendingCheckoutSchema,
  type CheckoutCommand, type CheckoutDependencies, type CheckoutError, type CheckoutResult, type CheckoutSpec, type CheckoutView, type PendingCheckout } from "./spec";

class StateFailure extends Error {
  constructor(readonly code: CheckoutError["code"]) { super(code); }
}
const messages: Record<CheckoutError["code"], string> = {
  INVALID_INPUT: "Review the checkout fields and quantities before sending.",
  INVALID_STORAGE: "Saved checkout metadata is invalid. Keep this tab and request operator review; no new key was created.",
  STORAGE_UNAVAILABLE: "Tab storage is unavailable. No new operation can be safely started until it is restored.",
  IDENTITY_CHANGED: "The saved guest identity is missing or changed. Restore it or request operator review; do not create a replacement key.",
  PAYLOAD_CHANGED: "An earlier checkout is unresolved. Check its result or close the unused checkout before submitting changed details.",
  OUTCOME_UNKNOWN: "The response was not confirmed. Keep this checkout key and check its result before retrying.",
  NOT_OBSERVED: "No committed order is visible yet. The original request may still arrive; keep this key or close the unused checkout safely.",
  BLOCKED: "This checkout needs review. Check its result; do not automatically resubmit with a new key.",
  BUSY: "A checkout action is already in progress.",
  UNEXPECTED_RESPONSE: "The server response could not be verified. Keep this key and check the result.",
};

export async function checkoutDigest(value: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))), byte => byte.toString(16).padStart(2, "0")).join("");
}

export class CheckoutHandler implements CheckoutSpec {
  private view: CheckoutView = { status: "idle" };
  private busy = false;
  constructor(private readonly dependencies: CheckoutDependencies) {}

  async execute(input: unknown): Promise<CheckoutResult> {
    const parsed = checkoutCommandSchema.safeParse(input);
    if (!parsed.success) return this.failure("INVALID_INPUT");
    if (this.busy) return this.failure("BUSY");
    this.busy = true;
    try {
      const operation = await this.load();
      this.view = operation ? { status: operation.phase, operation } : { status: "idle" };
      if (parsed.data.action === "submit") return await this.submit(parsed.data, operation);
      if (parsed.data.action === "acknowledge" && operation && operation.phase !== "unresolved") {
        await this.requireGuest(operation);
        this.requireCurrent(operation, [operation.phase]);
        try {
          this.dependencies.storage.removeItem(checkoutStorageKey);
          if (this.dependencies.storage.getItem(checkoutStorageKey) !== null) throw new Error("Storage retained the key");
        } catch { throw new StateFailure("STORAGE_UNAVAILABLE"); }
        this.view = { status: "idle" };
        return { success: true, state: this.view };
      }
      if (parsed.data.action === "close" && operation?.phase === "unresolved") {
        const guest = await this.requireGuest(operation);
        this.requireCurrent(operation, ["unresolved"]);
        const response = await this.dependencies.request("/api/orders/checkout/cancel", { method: "POST", cache: "no-store",
          headers: { "Idempotency-Key": operation.key, "X-Checkout-Session": guest },
          signal: AbortSignal.timeout(this.dependencies.timeoutMs ?? 15_000) });
        if (response.status !== 204) return this.failure("BLOCKED");
        return await this.closed(operation);
      }
      if (parsed.data.action === "recover" && operation && operation.phase !== "closed") {
        const guest = await this.requireGuest(operation);
        this.requireCurrent(operation, ["unresolved", "confirmed"]);
        const response = await this.dependencies.request("/api/orders/checkout", { method: "GET", cache: "no-store",
          headers: { "Idempotency-Key": operation.key, "X-Checkout-Session": guest },
          signal: AbortSignal.timeout(this.dependencies.timeoutMs ?? 15_000) });
        if (response.status === 404) return this.failure("NOT_OBSERVED");
        if (response.status === 410 && (await response.json().catch(() => null))?.code === "CHECKOUT_CANCELLED") return await this.closed(operation);
        if (!response.ok) return this.failure("BLOCKED");
        return await this.confirm(response, operation);
      }
      if (parsed.data.action !== "restore") return this.failure("BLOCKED");
      return { success: true, state: this.view };
    } catch (failure) {
      if (failure instanceof StateFailure) {
        if (["INVALID_STORAGE", "IDENTITY_CHANGED", "STORAGE_UNAVAILABLE"].includes(failure.code)) this.view = { status: "blocked" };
        return this.failure(failure.code);
      }
      return this.failure("OUTCOME_UNKNOWN");
    } finally { this.busy = false; }
  }

  private async submit(command: Extract<CheckoutCommand, { action: "submit" }>, operation: PendingCheckout | null): Promise<CheckoutResult> {
    if (operation && operation.phase !== "unresolved") return this.failure("BLOCKED");
    const quantities = new Map<string, number>();
    for (const line of command.cart.lines) {
      const sku = line.productId.toUpperCase();
      quantities.set(sku, (quantities.get(sku) ?? 0) + line.quantity);
    }
    if ([...quantities.values()].some(quantity => quantity > 99)) return this.failure("INVALID_INPUT");
    const payload = { customer: { ...command.customer, email: command.customer.email.toLowerCase(), paymentMethod: command.customer.paymentMethod.toUpperCase() },
      lines: [...quantities].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0).map(([sku, quantity]) => ({ sku, quantity })) };
    const payloadHash = await checkoutDigest(JSON.stringify(payload));
    if (operation && operation.payloadHash !== payloadHash) return this.failure("PAYLOAD_CHANGED");
    let guest = this.read(guestStorageKey);
    if (guest === null) {
      guest = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, "0")).join("");
      this.write(guestStorageKey, guest);
    }
    if (!hexDigestSchema.safeParse(guest).success) throw new StateFailure("IDENTITY_CHANGED");
    const capabilityHash = await checkoutDigest(guest);
    if (this.read(guestStorageKey) !== guest || (operation && operation.capabilityHash !== capabilityHash)) throw new StateFailure("IDENTITY_CHANGED");
    const pending: PendingCheckout = operation ?? { version: 1, key: crypto.randomUUID(), payloadHash,
      capabilityHash, createdAt: new Date().toISOString(), phase: "unresolved" };
    if (operation) this.requireCurrent(operation, ["unresolved"]);
    else if (this.read(checkoutStorageKey) !== null) throw new StateFailure("BUSY");
    this.write(checkoutStorageKey, JSON.stringify(pending));
    this.view = { status: "unresolved", operation: pending };
    const response = await this.dependencies.request("/api/orders", { method: "POST", cache: "no-store",
      headers: { "Content-Type": "application/json", "Idempotency-Key": pending.key, "X-Checkout-Session": guest },
      body: JSON.stringify(payload), signal: AbortSignal.timeout(this.dependencies.timeoutMs ?? 15_000) });
    if (!response.ok) return this.failure("BLOCKED");
    return await this.confirm(response, pending);
  }

  private async confirm(response: Response, operation: PendingCheckout): Promise<CheckoutResult> {
    const parsed = checkoutOrderSchema.safeParse(await response.json().catch(() => null));
    if (!parsed.success) return this.failure("UNEXPECTED_RESPONSE");
    await this.requireGuest(operation);
    this.requireCurrent(operation, ["unresolved", "confirmed"]);
    const confirmed = { ...operation, phase: "confirmed" as const };
    this.write(checkoutStorageKey, JSON.stringify(confirmed));
    this.view = { status: "confirmed", operation: confirmed, order: parsed.data };
    return { success: true, state: this.view };
  }

  private async closed(operation: PendingCheckout): Promise<CheckoutResult> {
    await this.requireGuest(operation);
    this.requireCurrent(operation, ["unresolved", "closed"]);
    const closed = { ...operation, phase: "closed" as const };
    this.write(checkoutStorageKey, JSON.stringify(closed));
    this.view = { status: "closed", operation: closed };
    return { success: true, state: this.view };
  }

  private write(key: string, value: string): void {
    try {
      this.dependencies.storage.setItem(key, value);
      if (this.dependencies.storage.getItem(key) !== value) throw new Error("Storage did not retain the value");
    } catch { throw new StateFailure("STORAGE_UNAVAILABLE"); }
  }

  private requireCurrent(operation: PendingCheckout, phases: PendingCheckout["phase"][]): void {
    const raw = this.read(checkoutStorageKey);
    let current: unknown;
    try { current = raw === null ? null : JSON.parse(raw); } catch { throw new StateFailure("INVALID_STORAGE"); }
    const parsed = pendingCheckoutSchema.safeParse(current);
    if (raw !== null && !parsed.success) throw new StateFailure("INVALID_STORAGE");
    if (!parsed.success || parsed.data.key !== operation.key || parsed.data.capabilityHash !== operation.capabilityHash
      || parsed.data.payloadHash !== operation.payloadHash || !phases.includes(parsed.data.phase)) throw new StateFailure("BUSY");
  }

  private async load(): Promise<PendingCheckout | null> {
    const raw = this.read(checkoutStorageKey);
    if (raw === null) return null;
    let decoded: unknown;
    try { decoded = JSON.parse(raw); } catch { throw new StateFailure("INVALID_STORAGE"); }
    const parsed = pendingCheckoutSchema.safeParse(decoded);
    if (!parsed.success) throw new StateFailure("INVALID_STORAGE");
    await this.requireGuest(parsed.data);
    return parsed.data;
  }

  private async requireGuest(operation: PendingCheckout): Promise<string> {
    const guest = this.read(guestStorageKey);
    if (!hexDigestSchema.safeParse(guest).success || await checkoutDigest(guest!) !== operation.capabilityHash
      || this.read(guestStorageKey) !== guest) throw new StateFailure("IDENTITY_CHANGED");
    return guest!;
  }

  private read(key: string): string | null {
    try { return this.dependencies.storage.getItem(key); } catch { throw new StateFailure("STORAGE_UNAVAILABLE"); }
  }

  private failure(code: CheckoutError["code"]): CheckoutResult {
    return { success: false, state: this.view, error: { code, message: messages[code], recoverable: !["INVALID_STORAGE", "IDENTITY_CHANGED"].includes(code) } };
  }
}
