import { describe, expect, it, vi } from "vitest";
import { advanceFulfillment, loadOperationsOrders, selectImportPayload, uploadLegacyCatalog } from "@/lib/operations-api";

describe("operations API", () => {
  it("uploads CSV with an explicit source identity and encoding", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          id: "job-1",
          sourceName: "legacy.csv",
          encoding: "UTF-8",
          status: "COMPLETED",
          totalRows: 1,
          validRows: 1,
          appliedRows: 1,
          rejectedRows: 0,
          checkpointRow: 2,
          createdAt: "2026-08-09T00:00:00Z",
        }),
        { status: 201 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    await uploadLegacyCatalog("legacy.csv", "UTF-8", "sku,vendor_code,name,on_hand,price_minor");

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(headers.get("X-Source-Name")).toBe("legacy.csv");
    expect(headers.get("X-Source-Encoding")).toBe("UTF-8");
    expect(headers.has("X-V-Market-Ops-Secret")).toBe(false);
  });

  it("loads the order queue and sends a single-step fulfillment transition", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("[]"))
      .mockResolvedValueOnce(new Response(JSON.stringify({ orderNumber: "VM-001", fulfillmentStatus: "PROCESSING", lines: [] })));
    vi.stubGlobal("fetch", fetchMock);

    await loadOperationsOrders();
    await advanceFulfillment("VM-001", "PROCESSING");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/ops/orders");
    expect(fetchMock.mock.calls[1]?.[0]).toBe("/api/ops/orders/VM-001/fulfillment");
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({
      method: "PATCH",
      body: JSON.stringify({ status: "PROCESSING" }),
    });
  });

  it("requires original file bytes when an operator declares CP932", () => {
    expect(() => selectImportPayload("CP932", "日本語", null)).toThrow(/original CP932 file/i);
    const bytes = new Uint8Array([0x93, 0xfa, 0x96, 0x7b]).buffer;
    expect(selectImportPayload("CP932", "ignored", bytes)).toBe(bytes);
    expect(selectImportPayload("UTF-8", "sku,name", null)).toBe("sku,name");
  });
});
