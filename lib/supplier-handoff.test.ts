import { expect, it } from "vitest";
import { receiptExpiry } from "./supplier-handoff";

it("uses the server receipt expiry and rejects malformed receipts", () => {
  const hash = "a".repeat(64);
  expect(receiptExpiry(`1793520600:${hash}:${hash}:${hash}`)).toBe(1793520600000);
  expect(receiptExpiry(`1793520600:${hash}:${hash}`)).toBeNull();
  expect(receiptExpiry(`1793520600:${hash}:${hash}:${hash}:extra`)).toBeNull();
});
