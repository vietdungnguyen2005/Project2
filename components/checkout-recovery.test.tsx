import React from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { CheckoutRecovery } from "./checkout-recovery";

it("offers result lookup and safe unused-key closure without suggesting a blind new checkout", () => {
  const html = renderToStaticMarkup(<CheckoutRecovery busy={false} onAction={() => undefined} state={{ status: "unresolved", operation: {
    version: 1, key: "11111111-1111-4111-8111-111111111111", payloadHash: "a".repeat(64), capabilityHash: "b".repeat(64),
    phase: "unresolved", createdAt: "2026-10-18T00:00:00Z",
  } }} />);
  expect(html).toContain("Check checkout result");
  expect(html).toContain("Close unused checkout");
  expect(html).not.toContain("Start a new checkout");
});
