export type MigrationSummary = {
  id: string;
  sourceName: string;
  encoding: string;
  status: "STAGED" | "RUNNING" | "COMPLETED" | "COMPLETED_WITH_ERRORS" | "FAILED";
  totalRows: number;
  validRows: number;
  appliedRows: number;
  rejectedRows: number;
  checkpointRow: number;
  createdAt: string;
};

export type Discrepancy = {
  id: string;
  sku: string;
  type: "MISSING_PRODUCT" | "QUANTITY_MISMATCH" | "PRICE_MISMATCH";
  expectedValue: string;
  actualValue: string;
  status: "OPEN" | "RESOLVED";
  resolutionNote: string | null;
};

export type ReconciliationReport = {
  id: string;
  migrationJobId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  mismatchCount: number;
  createdAt: string;
  discrepancies: Discrepancy[];
};

export type OperationsOrder = {
  orderNumber: string;
  paymentStatus: string;
  fulfillmentStatus: "RECEIVED" | "PROCESSING" | "SHIPPED" | "DELIVERED";
  grandTotalMinor: number;
  currency: "JPY";
  createdAt: string;
  lines: Array<{ sku: string; productName: string; quantity: number }>;
};

async function readOrThrow<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(payload?.message ?? "Operations request failed.");
  }
  return (await response.json()) as T;
}

export function selectImportPayload(
  encoding: "UTF-8" | "CP932",
  text: string,
  originalFileBytes: ArrayBuffer | null,
): string | ArrayBuffer {
  if (originalFileBytes) return originalFileBytes;
  if (encoding === "CP932") {
    throw new Error("Select the original CP932 file so its bytes can be decoded safely.");
  }
  return text;
}

export async function loadMigrationJobs(signal?: AbortSignal): Promise<MigrationSummary[]> {
  return readOrThrow(
    await fetch("/api/ops/imports", { cache: "no-store", signal }),
  );
}

export async function uploadLegacyCatalog(
  sourceName: string,
  encoding: "UTF-8" | "CP932",
  csv: string | ArrayBuffer,
): Promise<MigrationSummary> {
  return readOrThrow(
    await fetch("/api/ops/imports", {
      method: "POST",
      headers: {
        "Content-Type": "text/csv",
        "X-Source-Name": sourceName,
        "X-Source-Encoding": encoding,
      },
      body: csv,
    }),
  );
}

export async function loadReconciliations(
  signal?: AbortSignal,
): Promise<ReconciliationReport[]> {
  return readOrThrow(
    await fetch("/api/ops/reconciliations", { cache: "no-store", signal }),
  );
}

export async function runReconciliation(
  migrationJobId: string,
): Promise<ReconciliationReport> {
  return readOrThrow(
    await fetch("/api/ops/reconciliations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ migrationJobId }),
    }),
  );
}

export async function loadOperationsOrders(signal?: AbortSignal): Promise<OperationsOrder[]> {
  return readOrThrow(await fetch("/api/ops/orders", { cache: "no-store", signal }));
}

export async function advanceFulfillment(
  orderNumber: string,
  status: OperationsOrder["fulfillmentStatus"],
): Promise<OperationsOrder> {
  return readOrThrow(
    await fetch(`/api/ops/orders/${encodeURIComponent(orderNumber)}/fulfillment`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }),
  );
}
