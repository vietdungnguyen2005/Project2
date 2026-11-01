"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { receiptExpiry } from "@/lib/supplier-handoff";
import { confirmSupplier, loadIntegrationProfiles, loadMigrationJobs, previewSupplier, selectImportPayload,
  type IntegrationProfile, type MigrationSummary, type SupplierPreview } from "@/lib/operations-api";

const inputStyle="min-h-11 w-full border border-white/20 bg-[#0b0f0e] px-3 py-2 text-sm text-white outline-none focus:border-[#2de1c2] disabled:opacity-50";
const buttonStyle="min-h-11 border border-[#2de1c2]/50 px-4 py-2 text-sm font-bold text-[#7ef5df] hover:bg-[#2de1c2]/10 disabled:opacity-40";
type Review={profile:IntegrationProfile;body:string|ArrayBuffer;sourceName:string;result:SupplierPreview};

export function SupplierOnboarding({onImported}:{onImported:(job:MigrationSummary)=>Promise<void>}) {
  const queryClient=useQueryClient();
  const profiles=useQuery({queryKey:["integration-profiles"],queryFn:({signal})=>loadIntegrationProfiles(signal),retry:false});
  const [selected,setSelected]=useState("supplier-b:1");
  const [csv,setCsv]=useState("SKU,ProductName,AvailableQuantity\nVM-002,Modular desk,60\nVM-005,Linen baskets,invalid\n");
  const [sourceName,setSourceName]=useState("supplier-b-demo.csv");
  const [fileBytes,setFileBytes]=useState<ArrayBuffer|null>(null);
  const [fileLoading,setFileLoading]=useState(false);
  const [fileError,setFileError]=useState<string|null>(null);
  const [review,setReview]=useState<Review|null>(null);
  const [approved,setApproved]=useState(false);
  const [inspectionRequired,setInspectionRequired]=useState(false);
  const [refreshError,setRefreshError]=useState(false);
  const inspection=useMutation({retry:false,mutationFn:()=>loadMigrationJobs(),onSuccess:jobs=>queryClient.setQueryData(["migration-jobs"],jobs)});
  const [now,setNow]=useState(()=>Date.now());
  useEffect(()=>{const timer=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(timer);},[]);
  const expires=review?receiptExpiry(review.result.confirmationReceipt):null;
  const expired=review!==null && (expires===null || expires<=now);
  const profile=profiles.data?.find(item=>`${item.profileId}:${item.version}`===selected && item.active);
  const invalidate=()=>{setReview(null);setApproved(false);};
  const preview=useMutation({retry:false,mutationFn:async()=>{
    if (inspectionRequired) throw new Error("Inspect current Import runs before preparing another preview.");
    if (!profile) throw new Error("Select an active profile version.");
    const selectedBody=selectImportPayload(profile.encoding,csv,fileBytes);
    const body=typeof selectedBody==="string"?selectedBody:selectedBody.slice(0);
    const result=await previewSupplier(profile.profileId,profile.version,body);
    return {profile,body,sourceName,result};
  },onSuccess:value=>{setNow(Date.now());setReview(value);setApproved(false);}});
  const confirm=useMutation({retry:false,mutationFn:async()=>{
    if (inspectionRequired) throw new Error("Inspect current Import runs before any retry.");
    if (!review || !approved) throw new Error("Review and explicitly approve this preview first.");
    const expiry=receiptExpiry(review.result.confirmationReceipt);
    if (expiry===null || expiry<=Date.now()) throw new Error("Preview expired. Prepare and review a fresh preview.");
    return confirmSupplier(review.profile.profileId,review.profile.version,review.sourceName,review.body,review.result.confirmationReceipt);
  },onSuccess:job=>{invalidate();setRefreshError(false);void onImported(job).catch(()=>setRefreshError(true));},
  onError:()=>{setInspectionRequired(true);setApproved(false);inspection.reset();}});
  const busy=preview.isPending || confirm.isPending || fileLoading || inspectionRequired;

  return <section aria-labelledby="supplier-heading" className="mt-8 border border-[#2de1c2]/30 bg-[#111715] p-5 sm:p-6">
    <div className="border-b border-white/15 pb-4">
      <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">Supplier contract / Preview → Confirm → Reconcile</p>
      <h2 id="supplier-heading" className="mt-1 text-2xl font-black">Supplier onboarding</h2>
      <p className="mt-3 max-w-3xl text-sm leading-6 text-[#9bb3a6]">Synthetic suppliers, one shared store. Preview is read-only. Vendor, price and concurrency versions come from the canonical catalog; changed rows are rejected rather than overwriting later stock or metadata.</p>
    </div>
    <div className="mt-5 grid gap-6 lg:grid-cols-2">
      <form className="grid content-start gap-4" onSubmit={event=>{event.preventDefault();invalidate();preview.mutate();}}>
        <fieldset disabled={busy} className="grid gap-4 disabled:opacity-60">
          <label className="grid gap-2 text-sm text-[#c6d7cd]"><span id="supplier-profile-label">Supplier profile version</span>
            <select aria-labelledby="supplier-profile-label" value={selected} onChange={event=>{setSelected(event.target.value);setFileBytes(null);invalidate();preview.reset();confirm.reset();}} className={inputStyle}>
              <option value="">Select an active profile</option>
              {profiles.data?.filter(item=>item.active).map(item=><option key={`${item.profileId}:${item.version}`} value={`${item.profileId}:${item.version}`}>{item.profileId} / v{item.version} / {item.encoding}</option>)}
            </select>
          </label>
          {profile?<div className="border-l-2 border-[#2de1c2] bg-black/20 p-3 text-sm text-[#c6d7cd]">
            <p className="font-mono text-xs">Vendor {profile.vendorCode} · SKU {profile.skuNormalization} · name TRIM · stock integer 0–1,000,000</p>
            <dl className="mt-2 grid gap-1 sm:grid-cols-3">{Object.entries(profile.mapping).map(([field,column])=><div key={field} className="min-w-0 break-words"><dt className="font-mono text-xs text-[#7ef5df]">{field}</dt><dd>{column} (required)</dd></div>)}</dl>
          </div>:null}
          <label className="grid gap-2 text-sm text-[#c6d7cd]"><span id="supplier-source-label">Supplier source filename</span>
            <input aria-labelledby="supplier-source-label" className={inputStyle} value={sourceName} maxLength={160} required onChange={event=>{setSourceName(event.target.value);invalidate();}} />
          </label>
          <label className="grid gap-2 text-sm text-[#c6d7cd]"><span id="supplier-file-label">Supplier original CSV file</span>
            <input aria-labelledby="supplier-file-label" type="file" accept=".csv,text/csv" className={inputStyle} onChange={async event=>{
              invalidate();setFileBytes(null);setFileError(null);
              const file=event.target.files?.[0];if(!file)return;
              if(file.size>5_000_000){setFileError("Maximum upload size is 5,000,000 bytes.");return;}
              setFileLoading(true);
              try {setFileBytes(await file.arrayBuffer());setSourceName(file.name);}
              catch {setFileError("The file could not be read. Select it again.");}
              finally {setFileLoading(false);}
            }} />
            <span className="text-xs text-[#9bb3a6]">CP932 requires original bytes. Maximum 5,000,000 bytes and 1,000 data rows. No encoding guessing.</span>
          </label>
          <label className="grid gap-2 text-sm text-[#c6d7cd]"><span id="supplier-payload-label">Supplier CSV payload (UTF-8 only)</span>
            <textarea aria-labelledby="supplier-payload-label" className={`${inputStyle} font-mono text-xs leading-6`} rows={5} value={csv} onChange={event=>{setCsv(event.target.value);setFileBytes(null);setFileError(null);invalidate();}} spellCheck={false} />
          </label>
        </fieldset>
        <button className={buttonStyle} disabled={busy || !profile || !!fileError}>{preview.isPending?"Validating…":"Preview & validate"}</button>
        {profiles.error?<p role="alert" className="text-sm text-rose-300">{profiles.error.message} <button type="button" onClick={()=>void profiles.refetch()} className="underline">Reload profiles</button></p>:null}
        {preview.error || fileError?<p role="alert" className="text-sm text-rose-300">{fileError ?? preview.error?.message}</p>:null}
      </form>
      <div className="min-w-0 border border-white/10 bg-black/20 p-4" aria-live="polite">
        {!review?<p className="text-sm leading-6 text-[#9bb3a6]">01 / Choose a contract and preview the file. No inventory, orders, movements or import jobs are written until explicit confirmation.</p>:<>
          <h3 className="font-bold text-white">Mapping preview / {review.profile.profileId} v{review.profile.version}</h3>
          <p role="status" className="mt-2 text-sm text-amber-200">{expired?"Preview expired or receipt unavailable. Prepare and review a fresh preview.":`Preview expires ${new Date(expires!).toISOString()} · ${Math.max(0,Math.ceil((expires!-now)/1000))}s remaining. Java rechecks validity on confirmation.`}</p>
          <p className="mt-3 font-mono text-sm text-[#7ef5df]">{review.result.acceptedRows} accepted · {review.result.rejectedRows} rejected</p>
          <p className="mt-2 text-xs leading-5 text-[#9bb3a6]">First 10 accepted rows. Rejected rows are quarantined; validation is not a reservation. Catalog changes require a new preview or are rejected when applied.</p>
          <ul className="mt-3 grid gap-2">{review.result.sample.map(row=><li key={row.rowNumber} className="break-words border-l border-white/20 pl-3 text-sm text-[#c6d7cd]">Row {row.rowNumber} · {row.sku} · {row.name} · stock {row.onHand}</li>)}</ul>
          <details className="mt-4 text-sm text-amber-200" open={review.result.errors.length>0}><summary>Validation errors ({review.result.errors.length})</summary>
            <ul className="mt-2 max-h-56 overflow-auto">{review.result.errors.map(error=><li key={error.rowNumber} className="py-1">Row {error.rowNumber}: {error.reason}</li>)}</ul>
          </details>
          <button type="button" className={`${buttonStyle} mt-4`} onClick={()=>{
            const report={profileId:review.result.profileId,version:review.result.version,encoding:review.result.encoding,
              acceptedRows:review.result.acceptedRows,rejectedRows:review.result.rejectedRows,errors:review.result.errors};
            const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:"application/json"}));
            const link=document.createElement("a");link.href=url;link.download="supplier-validation-report.json";link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
          }}>Export sanitized report</button>
          <label className="mt-5 flex items-start gap-3 text-sm leading-6 text-[#c6d7cd]">
            <input type="checkbox" checked={approved} disabled={busy || expired} onChange={event=>setApproved(event.target.checked)} className="mt-1 size-5 shrink-0 accent-[#2de1c2]" />
            I reviewed this mapping, accepted rows and rejected rows, and approve applying this snapshot to the shared store.
          </label>
          <button type="button" disabled={!approved || busy || expired || review.result.acceptedRows===0} onClick={()=>confirm.mutate()} className={`${buttonStyle} mt-4 w-full bg-[#2de1c2]/10`}>{confirm.isPending?"Queueing…":"Confirm & queue"}</button>
        </>}
        {confirm.data?<p role="status" className="mt-4 break-all text-sm text-[#7ef5df]">Acknowledged job {confirm.data.id} · {confirm.data.status}. {confirm.data.status==="STAGED" || confirm.data.status==="RUNNING"?"Pending: stock application is not yet confirmed.":"Inspect Import runs for the recorded outcome."}</p>:null}
        {refreshError?<p role="alert" className="mt-4 text-sm text-amber-200">The job was acknowledged, but the list could not be refreshed. Reload Import runs to inspect its outcome.</p>:null}
        {inspectionRequired?<div className="mt-4 grid gap-3 border border-amber-300/40 p-3">
          <p role="alert" className="text-sm text-amber-200">Confirmation outcome requires inspection. A lost response may still have created an import. Sending is locked until you inspect current Import runs.</p>
          <button type="button" className={buttonStyle} disabled={inspection.isPending} onClick={()=>inspection.mutate()}>Inspect current Import runs</button>
          {inspection.error?<p role="alert" className="text-sm text-rose-300">Inspection failed. Sending remains locked. Sign in again if your session expired, then inspect.</p>:null}
          {inspection.isSuccess?<>
            <p className="text-xs text-[#c6d7cd]">Latest {inspection.data.length} visible imports. An absent job does not prove that nothing was applied. Compare the profile, source and outcome before proceeding.</p>
            <ul className="max-h-52 overflow-auto text-xs text-[#c6d7cd]">{inspection.data.map(job=><li key={job.id} className="break-all py-1">{job.id} · {job.status} · {job.profileId??"legacy"} v{job.profileVersion??"—"} · {job.sourceName} · {job.createdAt}</li>)}</ul>
            <button type="button" className={buttonStyle} onClick={()=>{invalidate();confirm.reset();preview.reset();inspection.reset();setInspectionRequired(false);}}>I inspected the jobs — prepare a fresh preview</button>
          </>:null}
        </div>:null}
      </div>
    </div>
  </section>;
}
