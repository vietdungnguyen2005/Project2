"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { inspectImport, restartImport, type MigrationSummary } from "@/lib/operations-api";

export function ImportInspection({jobId,onUpdated}:{jobId:string;onUpdated:(job:MigrationSummary)=>Promise<void>}) {
  const [opened,setOpened]=useState(false);
  const inspection=useQuery({queryKey:["import-inspection",jobId],queryFn:({signal})=>inspectImport(jobId,signal),enabled:opened,retry:false});
  const restart=useMutation({mutationFn:()=>restartImport(jobId),onSuccess:async job=>{await onUpdated(job);await inspection.refetch();},
    onError:async()=>{await inspection.refetch();}});
  const report=inspection.data;
  return <div className="w-full min-w-0 text-sm text-[#c6d7cd]">
    <button type="button" onClick={()=>{setOpened(true);if(opened)void inspection.refetch();}} disabled={inspection.isFetching} className="min-h-11 border border-white/30 px-4 text-xs font-bold text-white hover:border-[#2de1c2] disabled:opacity-40">{inspection.isFetching?"Reading…":"Inspect run"}</button>
    {opened && report?<div className="mt-3 border-l-2 border-amber-300/60 bg-black/20 p-3">
      <p className="font-mono text-xs">Work {report.workState} · recoveries {report.recoveryCount} · profile {report.job.profileId?`${report.job.profileId} v${report.job.profileVersion}`:"legacy"}</p>
      {report.lastRecoveryReason?<p className="mt-2 break-words text-amber-200">{report.lastRecoveryReason}</p>:null}
      <p className="mt-2 text-xs leading-5">{report.restartAllowed?"Safe restart reuses committed row markers and the existing job identity.":"No restart offered for this state. Active work must finish; BLOCKED or unknown ownership requires manual review. Refresh this inspection after the state changes."}</p>
      <ul className="mt-2 max-h-40 overflow-auto">{report.errors.map(error=><li key={error.rowNumber} className="break-words py-1 text-xs text-amber-200">Row {error.rowNumber}: {error.reason}</li>)}</ul>
      {report.restartAllowed?<button type="button" disabled={restart.isPending} onClick={()=>restart.mutate()} className="mt-3 min-h-11 border border-amber-300/50 px-4 text-xs font-bold text-amber-200 disabled:opacity-40">{restart.isPending?"Queueing…":"Restart failed import"}</button>:null}
    </div>:null}
    {inspection.error?<p role="alert" className="mt-2 text-rose-300">{inspection.error.message}</p>:null}
    {restart.error?<p role="alert" className="mt-2 text-rose-300">{restart.error.message} Refresh Import runs before retrying; the restart outcome may be unknown.</p>:null}
  </div>;
}
