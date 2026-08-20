import { describe, expect, it } from "vitest";
import { buildBackendHeaders } from "@/lib/backend-proxy";

describe("backend BFF boundary", () => {
  it("replaces browser-supplied trust headers with server-owned credentials", () => {
    const incoming = new Headers({
      "Content-Type": "application/json",
      "X-V-Market-Ops-Secret": "attacker-value",
      "X-V-Market-BFF-Secret": "attacker-value",
    });

    const headers = buildBackendHeaders(incoming, {
      bffSecret: "trusted-bff",
      opsSecret: "trusted-ops",
    });

    expect(headers.get("X-V-Market-BFF-Secret")).toBe("trusted-bff");
    expect(headers.get("X-V-Market-Ops-Secret")).toBe("trusted-ops");
    expect(headers.get("Content-Type")).toBe("application/json");
  });
});
