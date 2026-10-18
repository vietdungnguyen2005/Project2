import { describe, expect, it, vi } from "vitest";
import { advanceFulfillment, loadOperationsOrders, loadInventorySnapshot, restartImport, selectImportPayload, uploadLegacyCatalog, importPollingInterval } from "@/lib/operations-api";

describe("operations API", () => {
  it("polls active work only and stops automatic polling after a status-read error", () => {
    expect(importPollingInterval([{status:"STAGED"}],false)).toBe(2000);
    expect(importPollingInterval([{status:"RUNNING"}],false)).toBe(2000);
    expect(importPollingInterval([{status:"FAILED"},{status:"COMPLETED"}],false)).toBe(false);
    expect(importPollingInterval([{status:"RUNNING"}],true)).toBe(false);
    expect(importPollingInterval(undefined,false)).toBe(false);
  });
  it("requests asynchronous restart with the same job identity", async () => {
    const upstream=vi.fn().mockResolvedValueOnce(Response.json({authenticated:true,csrfToken:"csrf",roles:["ROLE_OPERATOR"]}))
      .mockResolvedValueOnce(Response.json({id:"job-1",status:"STAGED",appliedRows:50},{status:202}));
    vi.stubGlobal("fetch",upstream);
    await expect(restartImport("job-1")).resolves.toMatchObject({id:"job-1",status:"STAGED",appliedRows:50});
    expect(new Headers(upstream.mock.calls[1][1].headers).get("Prefer")).toBe("respond-async");
  });
  it("requests durable background processing and accepts a queued 202 response without claiming completion", async () => {
    const fetchMock=vi.fn().mockResolvedValueOnce(Response.json({ authenticated:true,csrfToken:"csrf",roles:["ROLE_OPERATOR"] }))
      .mockResolvedValueOnce(Response.json({ id:"queued-1",status:"STAGED",appliedRows:0 },{status:202}));
    vi.stubGlobal("fetch",fetchMock);
    await expect(uploadLegacyCatalog("queue.csv","UTF-8","csv")).resolves.toMatchObject({status:"STAGED",appliedRows:0});
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Prefer")).toBe("respond-async");
  });
  it("restarts the existing import with its operator CSRF session instead of uploading again", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ authenticated: true, csrfToken: "restart-csrf", roles: ["ROLE_OPERATOR"] }))
      .mockResolvedValueOnce(Response.json({ id: "job-1", status: "COMPLETED", appliedRows: 120 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(restartImport("job-1")).resolves.toMatchObject({ id: "job-1", appliedRows: 120 });
    expect(fetchMock.mock.calls[1][0]).toBe("/api/ops/imports/job-1/restart");
    expect(fetchMock.mock.calls[1][1].method).toBe("POST");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("X-CSRF-TOKEN")).toBe("restart-csrf");
  });
  it("loads a versioned inventory snapshot as CSV without fabricating stock versions", async () => {
    const csv = "schema_version,sku,vendor_code,name,on_hand,price_minor,expected_stock_version\n2,VM-001,NAMI,Jacket,31,11000,8\n";
    const fetchMock = vi.fn().mockResolvedValue(new Response(csv, { headers: { "Content-Type": "text/csv" } }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(loadInventorySnapshot()).resolves.toBe(csv);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/ops/inventory/snapshot");
  });
  it("uploads CSV with an explicit source identity and encoding", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({ authenticated: true, csrfToken: "csrf", roles: ["ROLE_OPERATOR"] })).mockResolvedValue(
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

    const [, options] = fetchMock.mock.calls[1] as [string, RequestInit];
    const headers = new Headers(options.headers);
    expect(headers.get("X-Source-Name")).toBe("legacy.csv");
    expect(headers.get("X-Source-Encoding")).toBe("UTF-8");
    expect(headers.has("X-V-Market-Ops-Secret")).toBe(false);
  });

  it("loads the order queue and sends a single-step fulfillment transition", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("[]"))
      .mockResolvedValueOnce(Response.json({ authenticated: true, csrfToken: "csrf", roles: ["ROLE_OPERATOR"] }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ orderNumber: "VM-001", fulfillmentStatus: "PROCESSING", lines: [] })));
    vi.stubGlobal("fetch", fetchMock);

    await loadOperationsOrders();
    await advanceFulfillment("VM-001", "PROCESSING");

    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/ops/orders");
    expect(fetchMock.mock.calls[2]?.[0]).toBe("/api/ops/orders/VM-001/fulfillment");
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
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
