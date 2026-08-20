"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, ArrowLeft, CheckCircle2, DatabaseZap, FileUp, PackageCheck, RefreshCw } from "lucide-react";
import { useMemo, useState } from "react";
import {
  advanceFulfillment,
  loadMigrationJobs,
  loadOperationsOrders,
  loadReconciliations,
  runReconciliation,
  selectImportPayload,
  uploadLegacyCatalog,
  type MigrationSummary,
} from "@/lib/operations-api";

const sampleCsv = `sku,vendor_code,name,on_hand,price_minor
VM-001,NAMI,AeroKnit travel jacket,32,11000
VM-002,RIVERBYTE,Modular desk organizer,58,5700`;

function statusStyle(status: MigrationSummary["status"]): string {
  if (status === "COMPLETED") return "border-emerald-400/40 bg-emerald-400/10 text-emerald-200";
  if (status === "COMPLETED_WITH_ERRORS") return "border-amber-400/40 bg-amber-400/10 text-amber-100";
  if (status === "FAILED") return "border-rose-400/40 bg-rose-400/10 text-rose-100";
  return "border-cyan-400/40 bg-cyan-400/10 text-cyan-100";
}

export function OperationsConsole() {
  const queryClient = useQueryClient();
  const [sourceName, setSourceName] = useState("legacy-catalog.csv");
  const [encoding, setEncoding] = useState<"UTF-8" | "CP932">("UTF-8");
  const [csv, setCsv] = useState(sampleCsv);
  const [fileBytes, setFileBytes] = useState<ArrayBuffer | null>(null);
  const jobs = useQuery({ queryKey: ["migration-jobs"], queryFn: ({ signal }) => loadMigrationJobs(signal) });
  const reconciliations = useQuery({
    queryKey: ["reconciliations"],
    queryFn: ({ signal }) => loadReconciliations(signal),
  });
  const orders = useQuery({
    queryKey: ["operations-orders"],
    queryFn: ({ signal }) => loadOperationsOrders(signal),
  });
  const upload = useMutation({
    mutationFn: () => uploadLegacyCatalog(sourceName, encoding, selectImportPayload(encoding, csv, fileBytes)),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ["migration-jobs"] }),
  });
  const reconcile = useMutation({
    mutationFn: runReconciliation,
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ["reconciliations"] }),
  });
  const advanceOrder = useMutation({
    mutationFn: ({ orderNumber, status }: { orderNumber: string; status: "PROCESSING" | "SHIPPED" | "DELIVERED" }) =>
      advanceFulfillment(orderNumber, status),
    onSuccess: async () => queryClient.invalidateQueries({ queryKey: ["operations-orders"] }),
  });

  const totals = useMemo(
    () => ({
      applied: jobs.data?.reduce((sum, job) => sum + job.appliedRows, 0) ?? 0,
      rejected: jobs.data?.reduce((sum, job) => sum + job.rejectedRows, 0) ?? 0,
      mismatches: reconciliations.data?.reduce((sum, run) => sum + run.mismatchCount, 0) ?? 0,
    }),
    [jobs.data, reconciliations.data],
  );
  const connectionError = jobs.error ?? reconciliations.error ?? orders.error;

  return (
    <main className="min-h-screen bg-[#0b0f0e] text-[#e8eee9]">
      <div className="pointer-events-none fixed inset-0 opacity-25 [background-image:repeating-linear-gradient(135deg,transparent_0,transparent_15px,rgba(45,225,194,.08)_15px,rgba(45,225,194,.08)_16px)]" />
      <div className="relative mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
        <header className="border-b border-white/15 pb-8">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <a href="/" className="inline-flex min-h-11 items-center gap-2 text-sm font-black uppercase tracking-[0.14em] text-[#9bb3a6] hover:text-white">
              <ArrowLeft aria-hidden className="size-4" /> Storefront
            </a>
            <span className="border border-[#2de1c2]/40 bg-[#2de1c2]/10 px-3 py-2 font-mono text-xs text-[#7ef5df]">
              JST / MIGRATION CONTROL
            </span>
          </div>
          <div className="mt-10 grid gap-8 lg:grid-cols-[1.4fr_.6fr] lg:items-end">
            <div>
              <p className="font-mono text-xs uppercase tracking-[0.2em] text-[#2de1c2]">移行運用 / Legacy modernization</p>
              <h1 className="mt-4 max-w-4xl text-balance text-5xl font-black leading-[0.92] sm:text-7xl">
                OPERATIONS
                <span className="block text-[#2de1c2]">LEDGER</span>
              </h1>
              <p className="mt-5 max-w-2xl text-base leading-7 text-[#9bb3a6]">
                Stage UTF-8 or CP932 vendor feeds, apply valid rows in restartable Spring Batch chunks, and reconcile drift against PostgreSQL.
              </p>
            </div>
            <dl className="grid grid-cols-3 border border-white/15 bg-black/20">
              {[
                ["Applied", totals.applied],
                ["Rejected", totals.rejected],
                ["Mismatch", totals.mismatches],
              ].map(([label, value]) => (
                <div key={label} className="border-r border-white/15 p-4 last:border-r-0">
                  <dt className="font-mono text-[10px] uppercase tracking-widest text-[#718278]">{label}</dt>
                  <dd className="mt-2 font-mono text-2xl font-black text-white">{value}</dd>
                </div>
              ))}
            </dl>
          </div>
        </header>

        {connectionError ? (
          <div role="alert" className="mt-6 flex gap-3 border border-amber-400/40 bg-amber-400/10 p-4 text-sm text-amber-100">
            <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0" />
            <p>{connectionError instanceof Error ? connectionError.message : "Backend unavailable."} No operational data is fabricated.</p>
          </div>
        ) : null}

        <section aria-labelledby="orders-heading" className="mt-8 border border-white/15 bg-[#111715] p-5 sm:p-6">
          <div className="flex flex-wrap items-end justify-between gap-4 border-b border-white/15 pb-4">
            <div>
              <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">00 / Transaction operations</p>
              <h2 id="orders-heading" className="mt-1 text-2xl font-black">Fulfillment queue</h2>
            </div>
            <p className="max-w-xl text-sm text-[#9bb3a6]">Only valid forward transitions are accepted; every change is written to the audit ledger.</p>
          </div>
          <div className="mt-4 grid gap-3 lg:grid-cols-2">
            {orders.data?.length ? orders.data.map((order) => {
              const next = order.fulfillmentStatus === "RECEIVED" ? "PROCESSING" : order.fulfillmentStatus === "PROCESSING" ? "SHIPPED" : order.fulfillmentStatus === "SHIPPED" ? "DELIVERED" : null;
              return (
                <article key={order.orderNumber} className="grid gap-4 border border-white/10 bg-black/20 p-4 sm:grid-cols-[1fr_auto] sm:items-center">
                  <div>
                    <div className="flex items-center gap-2"><PackageCheck aria-hidden className="size-4 text-[#2de1c2]" /><h3 className="font-mono font-bold">{order.orderNumber}</h3></div>
                    <p className="mt-2 text-sm text-[#9bb3a6]">{order.lines.map((line) => `${line.sku} × ${line.quantity}`).join(" · ")}</p>
                    <p className="mt-1 font-mono text-[10px] uppercase tracking-wider text-[#718278]">{order.paymentStatus} / {order.fulfillmentStatus}</p>
                  </div>
                  {next ? <button type="button" disabled={advanceOrder.isPending} onClick={() => advanceOrder.mutate({ orderNumber: order.orderNumber, status: next })} className="min-h-11 border border-[#2de1c2]/50 px-4 text-xs font-black uppercase tracking-wider text-[#7ef5df] hover:bg-[#2de1c2]/10 disabled:opacity-40">Mark {next.toLowerCase()}</button> : <span className="font-mono text-xs text-emerald-300">DELIVERED</span>}
                </article>
              );
            }) : <p className="py-6 text-sm text-[#718278]">No order is waiting for fulfillment.</p>}
          </div>
          {advanceOrder.error ? <p role="alert" className="mt-3 text-sm text-rose-300">{advanceOrder.error.message}</p> : null}
        </section>

        <div className="mt-8 grid gap-8 xl:grid-cols-[.8fr_1.2fr]">
          <section aria-labelledby="feed-heading" className="border border-white/15 bg-[#111715] p-5 shadow-[8px_8px_0_rgba(45,225,194,.12)] sm:p-6">
            <div className="flex items-center justify-between gap-4 border-b border-white/15 pb-4">
              <div>
                <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">01 / Staging gate</p>
                <h2 id="feed-heading" className="mt-1 text-2xl font-black">Vendor feed intake</h2>
              </div>
              <FileUp aria-hidden className="size-7 text-[#2de1c2]" />
            </div>
            <form
              className="mt-5 grid gap-4"
              onSubmit={(event) => {
                event.preventDefault();
                upload.mutate();
              }}
            >
              <label className="grid gap-2 font-mono text-xs uppercase tracking-wider text-[#9bb3a6]">
                Source filename
                <input value={sourceName} onChange={(event) => setSourceName(event.target.value)} required maxLength={160} className="h-11 border border-white/20 bg-black/30 px-3 font-sans text-sm normal-case tracking-normal text-white outline-none focus:border-[#2de1c2]" />
              </label>
              <label className="grid gap-2 font-mono text-xs uppercase tracking-wider text-[#9bb3a6]">
                Encoding
                <select value={encoding} onChange={(event) => setEncoding(event.target.value as "UTF-8" | "CP932")} className="h-11 border border-white/20 bg-[#0b0f0e] px-3 text-sm text-white outline-none focus:border-[#2de1c2]">
                  <option value="UTF-8">UTF-8</option>
                  <option value="CP932">CP932 / Windows-31J</option>
                </select>
              </label>
              <label className="grid gap-2 font-mono text-xs uppercase tracking-wider text-[#9bb3a6]">
                Original CSV file
                <input
                  type="file"
                  accept=".csv,text/csv"
                  onChange={async (event) => {
                    const file = event.target.files?.[0];
                    if (!file) {
                      setFileBytes(null);
                      return;
                    }
                    setSourceName(file.name);
                    setFileBytes(await file.arrayBuffer());
                  }}
                  className="min-h-11 border border-dashed border-white/30 bg-black/20 p-2 text-sm normal-case tracking-normal file:mr-3 file:border-0 file:bg-[#2de1c2] file:px-3 file:py-2 file:font-bold file:text-[#07110f]"
                />
                <span className="normal-case tracking-normal text-[#718278]">Required for CP932 so the browser preserves the original bytes.</span>
              </label>
              <label className="grid gap-2 font-mono text-xs uppercase tracking-wider text-[#9bb3a6]">
                CSV payload
                <textarea value={csv} onChange={(event) => { setCsv(event.target.value); setFileBytes(null); }} required={encoding === "UTF-8" && fileBytes === null} rows={10} spellCheck={false} className="resize-y border border-white/20 bg-black/30 p-3 font-mono text-xs leading-6 text-[#d7e1da] outline-none focus:border-[#2de1c2]" />
              </label>
              <button disabled={upload.isPending} className="inline-flex min-h-12 items-center justify-center gap-2 bg-[#2de1c2] px-5 text-sm font-black uppercase tracking-[0.12em] text-[#07110f] transition hover:bg-white disabled:cursor-wait disabled:opacity-60">
                <DatabaseZap aria-hidden className="size-4" /> {upload.isPending ? "Applying chunks…" : "Stage & apply"}
              </button>
              {upload.error ? <p role="alert" className="text-sm text-rose-300">{upload.error.message}</p> : null}
            </form>
          </section>

          <div className="grid gap-8">
            <section aria-labelledby="jobs-heading" className="border border-white/15 bg-[#111715] p-5 sm:p-6">
              <div className="flex items-center justify-between border-b border-white/15 pb-4">
                <div>
                  <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">02 / Checkpoint registry</p>
                  <h2 id="jobs-heading" className="mt-1 text-2xl font-black">Import runs</h2>
                </div>
                <RefreshCw aria-hidden className={`size-5 text-[#2de1c2] ${jobs.isFetching ? "animate-spin" : ""}`} />
              </div>
              <div className="mt-4 grid gap-3">
                {jobs.data?.length ? jobs.data.map((job) => (
                  <article key={job.id} className="grid gap-4 border border-white/10 bg-black/20 p-4 md:grid-cols-[1fr_auto] md:items-center">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className={`border px-2 py-1 font-mono text-[10px] ${statusStyle(job.status)}`}>{job.status}</span>
                        <span className="font-mono text-[10px] text-[#718278]">{job.encoding}</span>
                      </div>
                      <h3 className="mt-2 truncate font-bold text-white">{job.sourceName}</h3>
                      <p className="mt-1 font-mono text-xs text-[#718278]">checkpoint {job.checkpointRow} · applied {job.appliedRows} · rejected {job.rejectedRows}</p>
                    </div>
                    <button type="button" disabled={reconcile.isPending || job.appliedRows === 0} onClick={() => reconcile.mutate(job.id)} className="min-h-11 border border-[#2de1c2]/50 px-4 text-xs font-black uppercase tracking-wider text-[#7ef5df] hover:bg-[#2de1c2]/10 disabled:opacity-40">
                      Reconcile
                    </button>
                  </article>
                )) : <p className="py-8 text-center text-sm text-[#718278]">No migration run recorded.</p>}
              </div>
            </section>

            <section aria-labelledby="reconciliation-heading" className="border border-white/15 bg-[#111715] p-5 sm:p-6">
              <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">03 / Drift evidence</p>
              <h2 id="reconciliation-heading" className="mt-1 text-2xl font-black">Reconciliation reports</h2>
              <div className="mt-5 grid gap-3">
                {reconciliations.data?.length ? reconciliations.data.map((run) => (
                  <article key={run.id} className="border-l-2 border-[#2de1c2] bg-black/20 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        {run.mismatchCount === 0 ? <CheckCircle2 aria-hidden className="size-5 text-emerald-300" /> : <AlertTriangle aria-hidden className="size-5 text-amber-300" />}
                        <h3 className="font-bold">{run.mismatchCount} mismatch{run.mismatchCount === 1 ? "" : "es"}</h3>
                      </div>
                      <time className="font-mono text-[10px] text-[#718278]">{new Date(run.createdAt).toLocaleString("ja-JP")}</time>
                    </div>
                    {run.discrepancies.map((item) => (
                      <div key={item.id} className="mt-3 grid gap-1 border-t border-white/10 pt-3 font-mono text-xs sm:grid-cols-[7rem_1fr]">
                        <span className="text-[#2de1c2]">{item.sku}</span>
                        <span className="text-[#9bb3a6]">{item.type}: expected {item.expectedValue}, actual {item.actualValue}</span>
                      </div>
                    ))}
                  </article>
                )) : <p className="py-6 text-sm text-[#718278]">Run reconciliation after an import to produce drift evidence.</p>}
              </div>
            </section>
          </div>
        </div>
      </div>
    </main>
  );
}
