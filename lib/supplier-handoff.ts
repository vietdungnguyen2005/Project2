/** Display only: Java verifies the signature, actor, expiry and exact original bytes. */
export function receiptExpiry(receipt: string): number | null {
  const match = /^([0-9]{10,12}):[a-f0-9]{64}:[a-f0-9]{64}:[a-f0-9]{64}$/.exec(receipt);
  return match ? Number(match[1]) * 1000 : null;
}
