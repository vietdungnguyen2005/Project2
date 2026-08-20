import { describe, expect, it, vi } from "vitest";
import { trackOrder } from "@/lib/tracking-api";

describe("order tracking API", () => {
  it("keeps the opaque token in the same-origin BFF request", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await trackOrder("VM-ABC", "token+/=");

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/orders/VM-ABC?trackingToken=token%2B%2F%3D",
      expect.objectContaining({ cache: "no-store" }),
    );
  });
});
