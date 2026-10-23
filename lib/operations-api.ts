import { operatorFetch } from "@/lib/operator-session";

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
  importMode?: "METADATA_ONLY" | "VERSIONED_STOCK" | "LEGACY_UNVERIFIED";
  profileId?: string | null;
  profileVersion?: number | null;
};

export type IntegrationProfile = {
  profileId: string; version: number; encoding: "UTF-8" | "CP932"; vendorCode: string;
  mapping: { sku: string; name: string; on_hand: string }; skuNormalization: "TRIM" | "TRIM_UPPER";
  active: boolean; createdBy: string; createdAt: string; updatedBy: string; updatedAt: string;
};
export type ValidationError = { rowNumber: number; reason: string };
export type SupplierPreview = {
  profileId: string; version: number; encoding: string; mapping: IntegrationProfile["mapping"];
  acceptedRows: number; rejectedRows: number;
  sample: Array<{ rowNumber: number; sku: string; name: string; onHand: number }>;
  errors: ValidationError[]; confirmationReceipt: string;
};
export type ImportInspection = {
  job: MigrationSummary; workState: "READY" | "CLAIMED" | "FINISHED" | "FAILED" | "BLOCKED";
  recoveryCount: number; lastRecoveryReason: string | null; restartAllowed: boolean; errors: ValidationError[];
};

export async function loadIntegrationProfiles(signal?: AbortSignal): Promise<IntegrationProfile[]> {
  return readOrThrow(await operatorFetch("/api/ops/integration-profiles", { signal }));
}
function onboardingPath(id: string, version: number, action: "preview" | "confirm"): string {
  return `/api/ops/onboarding/${encodeURIComponent(id)}/${version}/${action}`;
}
export async function previewSupplier(id: string, version: number, csv: string | ArrayBuffer): Promise<SupplierPreview> {
  return readOrThrow(await operatorFetch(onboardingPath(id,version,"preview"), {
    method:"POST",headers:{"Content-Type":"text/csv"},body:csv,
  }));
}
export async function confirmSupplier(id: string, version: number, sourceName: string, csv: string | ArrayBuffer, receipt: string): Promise<MigrationSummary> {
  return readOrThrow(await operatorFetch(onboardingPath(id,version,"confirm"), {
    method:"POST",headers:{"Content-Type":"text/csv","X-Source-Name":sourceName,"X-Preview-Receipt":receipt},body:csv,
  }));
}
export async function inspectImport(jobId: string, signal?: AbortSignal): Promise<ImportInspection> {
  return readOrThrow(await operatorFetch(`/api/ops/imports/${encodeURIComponent(jobId)}/inspection`,{signal}));
}

export type Discrepancy = {
  id: string;
  sku: string;
  type: "MISSING_PRODUCT" | "QUANTITY_MISMATCH" | "PRICE_MISMATCH" | "STOCK_VERSION_REGRESSION" | "PRODUCT_VERSION_REGRESSION";
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
  changedSinceImportRows?: number;
  stockNotVerifiedRows?: number;
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
    await operatorFetch("/api/ops/imports", { cache: "no-store", signal }),
  );
}

export function importPollingInterval(jobs: ReadonlyArray<Pick<MigrationSummary,"status">> | undefined,failed: boolean): 2000 | false {
  return !failed && jobs?.some(job=>job.status==="STAGED" || job.status==="RUNNING") ? 2000 : false;
}

export async function loadInventorySnapshot(signal?: AbortSignal): Promise<string> {
  const response = await operatorFetch("/api/ops/inventory/snapshot", { cache: "no-store", signal });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as { message?: string } | null;
    throw new Error(payload?.message ?? "Inventory snapshot request failed.");
  }
  return response.text();
}

export async function restartImport(jobId: string): Promise<MigrationSummary> {
  return readOrThrow(await operatorFetch(`/api/ops/imports/${encodeURIComponent(jobId)}/restart`, { method: "POST",headers:{Prefer:"respond-async"} }));
}

export async function uploadLegacyCatalog(
  sourceName: string,
  encoding: "UTF-8" | "CP932",
  csv: string | ArrayBuffer,
): Promise<MigrationSummary> {
  return readOrThrow(
    await operatorFetch("/api/ops/imports", {
      method: "POST",
      headers: {
        "Content-Type": "text/csv",
        "X-Source-Name": sourceName,
        "X-Source-Encoding": encoding,
        Prefer: "respond-async",
      },
      body: csv,
    }),
  );
}

export async function loadReconciliations(
  signal?: AbortSignal,
): Promise<ReconciliationReport[]> {
  return readOrThrow(
    await operatorFetch("/api/ops/reconciliations", { cache: "no-store", signal }),
  );
}

export async function runReconciliation(
  migrationJobId: string,
): Promise<ReconciliationReport> {
  return readOrThrow(
    await operatorFetch("/api/ops/reconciliations", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ migrationJobId }),
    }),
  );
}

export async function loadOperationsOrders(signal?: AbortSignal): Promise<OperationsOrder[]> {
  return readOrThrow(await operatorFetch("/api/ops/orders", { cache: "no-store", signal }));
}

export async function advanceFulfillment(
  orderNumber: string,
  status: OperationsOrder["fulfillmentStatus"],
): Promise<OperationsOrder> {
  return readOrThrow(
    await operatorFetch(`/api/ops/orders/${encodeURIComponent(orderNumber)}/fulfillment`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }),
  );
}
