"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { loadIntegrationProfiles } from "@/lib/operations-api";
import { copilotRequest, type ClientReply } from "@/lib/copilot-client";
import { Answer, Source, type PolicyAnswer, type PolicySource } from "@/lib/ai-qa/spec";
import { RunReply, observationLines, toolLabels, type Investigation } from "@/lib/copilot-runs";
import { uuid } from "@/lib/ai-gateway/spec";

const control="min-h-11 border border-white/25 bg-[#0b0f0e] px-3 py-2 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#2de1c2] disabled:opacity-50";
const primary=control+" bg-[#2de1c2] font-bold text-[#0b0f0e]";
type Failure=Extract<ClientReply<never>,{success:false}>;
const messages:Record<string,string>={AI_DISABLED:"This Copilot capability is disabled.",SESSION_EXPIRED:"Your session has expired. Sign in again.",ACCESS_DENIED:"Current access could not be verified. Results have been cleared.",DOCUMENT_NOT_AVAILABLE:"This source is no longer available with current access.",STEP_CONFLICT:"The profile or run changed. Refresh it before continuing.",DEADLINE_EXCEEDED:"The request stopped before a result was received. It was not retried.",BUDGET_EXCEEDED:"The request limit was reached. Continue manually or try later.",OUTPUT_INVALID:"The response could not be verified. Continue with the manual workflow."};
function ErrorNotice({error}:{error:Failure|null}){return error&&<div role="alert" className="mt-4 border border-amber-300/40 bg-amber-300/10 p-3 text-sm text-amber-100">
  <p>{messages[error.code]??"A required service is unavailable. The manual workflow remains available."}</p>
  {error.request_id&&<p className="mt-2 break-all font-mono text-xs">Trace: {error.request_id}</p>}
</div>;}

function PolicyQuestions(){
  const profiles=useQuery({queryKey:["integration-profiles"],queryFn:({signal})=>loadIntegrationProfiles(signal),retry:false});
  const [selected,setSelected]=useState("");const [question,setQuestion]=useState("");const [language,setLanguage]=useState<"en"|"ja">("en");
  const [answer,setAnswer]=useState<(PolicyAnswer&{profile_id:string;profile_version:number})|null>(null);
  const [source,setSource]=useState<PolicySource|null>(null),[sourceOpen,setSourceOpen]=useState(false);
  const [error,setError]=useState<Failure|null>(null),[busy,setBusy]=useState(false);
  const request=useRef<AbortController|null>(null),dialog=useRef<HTMLDialogElement|null>(null);
  const sourceTrigger=useRef<HTMLElement|null>(null);
  const active=(profiles.data??[]).filter(p=>p.active);
  const profile=active.find(p=>`${p.profileId}:${p.version}`===selected)??(!selected?active[0]:undefined);
  const profileKey=profile?`${profile.profileId}:${profile.version}`:"";
  const visibleAnswer=answer&&`${answer.profile_id}:${answer.profile_version}`===profileKey?answer:null;
  useEffect(()=>()=>request.current?.abort(),[]);
  useEffect(()=>{if(sourceOpen&&!dialog.current?.open)dialog.current?.showModal();},[sourceOpen]);
  function clear(){request.current?.abort();setAnswer(null);setSource(null);setSourceOpen(false);setError(null);setBusy(false);}
  function stop(){request.current?.abort();setBusy(false);setError({success:false,code:"DEADLINE_EXCEEDED",status:499});}
  async function ask(event:FormEvent){
    event.preventDefault();if(!profile||busy)return;
    const controller=new AbortController();request.current=controller;setBusy(true);setAnswer(null);setError(null);
    const reply=await copilotRequest("ask",{question,language,profile_id:profile.profileId,profile_version:profile.version},Answer,controller.signal);
    if(controller.signal.aborted)return;setBusy(false);
    if(reply.success)setAnswer({...reply.data,profile_id:profile.profileId,profile_version:profile.version});else setError(reply);
  }
  async function openSource(chunk_id:string){
    if(!visibleAnswer||busy)return;
    sourceTrigger.current=document.activeElement instanceof HTMLElement?document.activeElement:null;
    const controller=new AbortController();request.current=controller;setBusy(true);setSource(null);setError(null);setSourceOpen(true);
    const reply=await copilotRequest("source",{chunk_id,profile_id:visibleAnswer.profile_id,profile_version:visibleAnswer.profile_version},Source,controller.signal);
    if(controller.signal.aborted)return;setBusy(false);
    if(reply.success)setSource(reply.data);else{setError(reply);if(reply.status===401||reply.status===403)setAnswer(null);}
  }
  const closeSource=()=>{request.current?.abort();dialog.current?.close();setSourceOpen(false);setSource(null);setBusy(false);requestAnimationFrame(()=>sourceTrigger.current?.focus());};
  return <div className="min-w-0">
    <h3 className="text-lg font-bold">Supplier policy Q&amp;A</h3>
    <p className="mt-2 text-sm leading-6 text-[#9bb3a6]">Ask about a supplier’s documented policy. Answers quote supported source text; a missing answer stays unresolved.</p>
    <form onSubmit={event=>void ask(event)} className="mt-4 space-y-4">
      <div className="grid gap-3 sm:grid-cols-[1fr_8rem]">
        <label className="text-sm">Supplier policy profile<select value={profileKey} onChange={e=>{clear();setSelected(e.target.value);}} disabled={busy} className={control+" mt-2 block w-full"}>
          {!active.length&&<option value="">{profiles.isPending?"Loading profiles…":"No active profiles"}</option>}
          {active.map(p=><option key={`${p.profileId}:${p.version}`} value={`${p.profileId}:${p.version}`}>{p.profileId} / v{p.version} / {p.vendorCode}</option>)}
        </select></label>
        <label className="text-sm">Language<select value={language} onChange={e=>{clear();setLanguage(e.target.value as "en"|"ja");}} disabled={busy} className={control+" mt-2 block w-full"}><option value="en">English</option><option value="ja">日本語</option></select></label>
      </div>
      <div><label htmlFor="copilot-policy-question" className="block text-sm">Policy question</label><textarea id="copilot-policy-question" value={question} onChange={e=>{clear();setQuestion(e.target.value);}} required maxLength={2000} rows={3} disabled={busy} className={control+" mt-2 block w-full resize-y"} placeholder="Which encoding preserves the original supplier bytes?"/></div>
      <div className="flex flex-wrap gap-3"><button type="submit" disabled={busy||!profile||!question.trim()} className={primary}>Ask with sources</button>{busy&&<button type="button" onClick={stop} className={control}>Stop waiting</button>}</div>
    </form>
    {profiles.isError&&<p role="alert" className="mt-3 text-sm text-amber-100">Supplier profiles are unavailable. Retry the manual workflow when Java is reachable.</p>}
    {busy&&!sourceOpen&&<p role="status" className="mt-4 text-sm text-[#7ef5df]">Checking policy evidence…</p>}
    {!sourceOpen&&<ErrorNotice error={error}/>}
    {visibleAnswer&&<article aria-label="Policy result" className="mt-5 border-l-2 border-[#2de1c2] bg-black/20 p-4">
      <h4 className="font-bold">{visibleAnswer.status==="answered"?"Suggested policy answer":"No supported answer"}</h4>
      <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{visibleAnswer.answer??({NO_SUPPORTED_EVIDENCE:"No current source directly supports an answer.",CONFLICTING_EVIDENCE:"Current sources conflict. Ask a human to resolve them.",OUTDATED_ONLY:"Only outdated evidence was found.",AMBIGUOUS_REQUEST:"The request needs clarification."}[visibleAnswer.reason??"NO_SUPPORTED_EVIDENCE"])}</p>
      <div className="mt-3 flex flex-wrap gap-2">{visibleAnswer.citations.map((c,index)=><button key={c.chunk_id} type="button" disabled={busy} onClick={()=>void openSource(c.chunk_id)} className={control+" text-[#7ef5df]"}>Source {index+1} · {c.locator.kind} {c.locator.value}</button>)}</div>
      <details className="mt-3 text-xs text-[#9bb3a6]"><summary className="cursor-pointer py-2">Response details</summary><p className="break-all">Trace: {visibleAnswer.request_id}</p><p>Provider mode: {visibleAnswer.provider_mode}. Quote provenance checked; semantic confidence is not calibrated.</p></details>
    </article>}
    {sourceOpen&&<dialog ref={dialog} aria-labelledby="policy-source-title" onCancel={e=>{e.preventDefault();closeSource();}} className="fixed inset-0 m-auto max-h-[85vh] w-[calc(100%-2rem)] max-w-2xl overflow-y-auto border border-[#2de1c2]/50 bg-[#111715] p-5 text-[#e8eee9] backdrop:bg-black/75">
      <div className="flex items-center justify-between gap-3"><h3 id="policy-source-title" className="text-xl font-bold">Current policy source</h3><button type="button" onClick={closeSource} className={control} autoFocus>Close source</button></div>
      {busy&&<p role="status" className="mt-4">Checking current source access…</p>}
      <ErrorNotice error={error}/>
      {source&&<><p className="mt-5 font-mono text-xs text-[#7ef5df]">{source.citation.locator.kind} {source.citation.locator.value}</p><blockquote className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{source.excerpt}</blockquote>{source.truncated&&<p className="mt-3 text-sm text-amber-100">Excerpt limited to 2,000 characters.</p>}<details className="mt-4 break-all text-xs text-[#9bb3a6]"><summary className="py-2">Source references</summary><p>Document: {source.citation.document_id}</p><p>Version: {source.citation.version_id}</p><p>Chunk: {source.citation.chunk_id}</p><p>Trace: {source.request_id}</p></details></>}
    </dialog>}
  </div>;
}

export function OperatorCopilot({qaEnabled,runsEnabled}:{qaEnabled:boolean;runsEnabled:boolean}){
  return <section aria-labelledby="copilot-heading" className="mt-8 border border-[#2de1c2]/40 bg-[#111715] p-5 sm:p-6">
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4 border-b border-white/15 pb-5"><div><p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">AI / Evidence desk</p><h2 id="copilot-heading" className="mt-1 text-2xl font-black">Operator Copilot</h2></div><p className="max-w-lg text-sm leading-6 text-[#9bb3a6]">Grounded policy answers and bounded investigations. Mapping edits, profile activation and import confirmation stay in your review workflow.</p></div>
    <div className="grid gap-8 xl:grid-cols-2">
    {!qaEnabled&&<p>Policy Q&amp;A is disabled.</p>}
    {qaEnabled&&<PolicyQuestions/>}
    {!runsEnabled&&<p>Import investigation is disabled.</p>}
    {runsEnabled&&<ImportInvestigation/>}
    </div>
    <a href="#supplier-heading" className="inline-flex min-h-11 items-center text-[#7ef5df] underline">Continue with manual supplier workflow</a>
  </section>;
}

function ImportInvestigation(){
  const [question,setQuestion]=useState(""),[reference,setReference]=useState("");
  const [result,setResult]=useState<Investigation|null>(null),[busy,setBusy]=useState(false),[pending,setPending]=useState("");
  const [askedQuestion,setAskedQuestion]=useState("");
  const [history,setHistory]=useState<{question:string;run:Investigation["run"]}[]>([]);
  const [error,setError]=useState<Failure|null>(null),[trace,setTrace]=useState<string|undefined>();
  const request=useRef<AbortController|null>(null);
  useEffect(()=>{try{const id=sessionStorage.getItem("vmarket-copilot-run");if(uuid.safeParse(id).success)setReference(id!);}catch{/* Storage can be disabled. */}return ()=>request.current?.abort();},[]);
  function remember(id:string){setReference(id);try{sessionStorage.setItem("vmarket-copilot-run",id);}catch{/* Reference remains visible. */}}
  function forget(){request.current?.abort();setReference("");setAskedQuestion("");setResult(null);setError(null);setTrace(undefined);try{sessionStorage.removeItem("vmarket-copilot-run");}catch{/* Storage can be disabled. */}}
  function nextQuestion(){if(!result||result.run.can_cancel)return;setHistory(items=>[...items,{question:askedQuestion,run:result.run}].slice(-5));forget();setQuestion("");requestAnimationFrame(()=>document.getElementById("copilot-investigation-question")?.focus());}
  function stop(){request.current?.abort();setBusy(false);setPending("");setResult(null);setError({success:false,code:"DEADLINE_EXCEEDED",status:499});}
  async function command(action:"start"|"inspect"|"resume"|"cancel"){
    if(busy)return;
    const controller=new AbortController();request.current=controller;setBusy(true);setError(null);setTrace(undefined);
    const previous=result;setResult(null);
    if(action==="start")setAskedQuestion(question);
    setPending(action==="start"?"Saving investigation reference…":action==="resume"?"Reading authorized tools…":"Checking saved run…");
    const input=action==="start"?{action,question}:action==="inspect"?{action,run_id:reference}:{action,run_id:reference,expected_revision:previous?.run.revision};
    let reply=await copilotRequest("run",input,RunReply,controller.signal);
    if(controller.signal.aborted)return;
    if(reply.success){remember(reply.data.run.run_id);setResult(reply.data);
      if(action==="start"&&reply.data.run.can_resume){
        const saved=reply.data.run;setPending("Reading authorized tools…");
        reply=await copilotRequest("run",{action:"resume",run_id:saved.run_id,expected_revision:saved.revision},RunReply,controller.signal);
        if(controller.signal.aborted)return;
      }
    }
    setBusy(false);setPending("");setTrace(reply.request_id);
    if(reply.success)setResult(reply.data);else{setResult(null);if(reply.status===401||reply.status===403)forget();setError(reply);}
  }
  return <div className="min-w-0">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-lg font-bold">Import investigation</h3><span className="font-mono text-xs uppercase tracking-wider text-[#7ef5df]">Read-only tools / 3 reads max</span></div>
    <p className="mt-2 text-sm leading-6 text-[#9bb3a6]">Investigate current import jobs, profiles and reconciliation evidence. Each question starts an independent run. Business changes return to the human workflow below.</p>
    {history.length>0&&<ol aria-label="Investigation conversation" className="mt-4 space-y-3">{history.map(turn=><li key={turn.run.run_id} className="border border-white/15 p-3 text-sm"><p className="whitespace-pre-wrap break-words font-bold">{turn.question||"Inspected saved investigation"}</p><p className="mt-2 text-[#9bb3a6]">Historical run: {turn.run.status} · {turn.run.step} read attempts. {turn.run.outcome?.kind==="handoff"?"Human action remains pending.":"Full records require a current authorized read."}</p></li>)}</ol>}
    <form onSubmit={e=>{e.preventDefault();void command("start");}} className="mt-4 space-y-3">
      <div><label htmlFor="copilot-investigation-question" className="block text-sm">Investigation question</label><textarea id="copilot-investigation-question" value={question} onChange={e=>setQuestion(e.target.value)} required maxLength={2000} rows={3} disabled={busy||Boolean(reference)} className={control+" mt-2 block w-full resize-y"} placeholder="List the current import jobs and their status."/></div>
      <div className="flex flex-wrap gap-3"><button type="submit" disabled={busy||!question.trim()||Boolean(reference)} className={primary}>Start investigation</button>{busy&&<button type="button" onClick={stop} className={control}>Stop waiting</button>}</div>
    </form>
    <details className="mt-4 border border-white/15 p-3" open={Boolean(reference)}><summary className="cursor-pointer text-sm text-[#9bb3a6]">Saved run controls</summary>
      <label className="mt-3 block text-sm">Saved run reference<input value={reference} onChange={e=>{setReference(e.target.value);setAskedQuestion("");setResult(null);setError(null);}} maxLength={36} disabled={busy} spellCheck={false} className={control+" mt-2 block w-full font-mono text-xs"}/></label>
      <div className="mt-3 flex flex-wrap gap-2"><button type="button" onClick={()=>void command("inspect")} disabled={busy||!uuid.safeParse(reference).success} className={control}>Inspect saved run</button>
        {result?.run.can_resume&&<button type="button" onClick={()=>void command("resume")} disabled={busy} className={control}>Resume read-only investigation</button>}
        {result?.run.can_cancel&&<button type="button" onClick={()=>void command("cancel")} disabled={busy} className={control}>Cancel investigation</button>}
        {reference&&<button type="button" onClick={forget} disabled={busy} className={control}>Forget reference</button>}
      </div><p className="mt-3 text-xs leading-5 text-[#9bb3a6]">After a timeout or reload, inspect explicitly. No automatic retry or resume. Forgetting a reference does not cancel its run; inactive runs expire after 10 minutes. Only the reference is kept in this tab’s session storage.</p>
    </details>
    {busy&&<p role="status" className="mt-4 text-sm text-[#7ef5df]">{pending}</p>}<ErrorNotice error={error}/>
    {result&&<article aria-label="Investigation result" className="mt-5 border-l-2 border-[#2de1c2] bg-black/20 p-4">
      <h4 className="font-bold">{result.run.outcome?.kind==="handoff"?"Human action required":result.run.status==="completed"?"Investigation finished":`Investigation ${result.run.status.replaceAll("_"," ")}`}</h4>
      {askedQuestion&&<p className="mt-3 whitespace-pre-wrap break-words text-sm">Question: {askedQuestion}</p>}
      <p className="mt-2 text-sm text-[#9bb3a6]">Run status: {result.run.status} · {result.run.step} read attempts. Import job status is shown only in observed tool results.</p>
      {result.run.reason&&<p className="mt-2 font-mono text-xs">{result.run.reason}</p>}
      {result.run.outcome?.status==="abstained"&&<p className="mt-3 text-sm text-amber-100">The investigation could not establish a supported conclusion. Review the manual records.</p>}
      {result.run.outcome?.kind==="handoff"&&<p className="mt-3 text-sm">{result.run.outcome.handoff_reason?.replaceAll("_"," ")} remains pending. <a href="#supplier-heading" className="text-[#7ef5df] underline">Review the current profile, preview and explicit human confirmation</a>.</p>}
      <ol aria-label="Tools actually called" className="mt-4 space-y-3">{result.run.observations.map((observation,index)=>{
        const live=result.observations.find(o=>o.call_id===observation.call_id);
        return <li key={observation.call_id} className="min-w-0 border border-white/15 p-3"><p className="text-sm font-bold">{index+1}. {toolLabels[observation.tool_name]}</p><p className="mt-1 break-words font-mono text-xs text-[#9bb3a6]">{observation.status} · {observation.observed_at} · {observation.item_count} items</p>
          {live?<ul className="mt-3 space-y-2 text-sm leading-6">{observationLines(live).map((line,i)=><li key={i} className="whitespace-pre-wrap break-words [overflow-wrap:anywhere]">{line}</li>)}</ul>:<p className="mt-2 text-xs text-[#9bb3a6]">Historical summary only. Read details are not replayed from storage.</p>}
          {(observation.truncated||observation.tool_name==="get_integration_profiles"||observation.tool_name==="inspect_import_job")&&<p className="mt-2 text-xs text-amber-100">Details may be limited. Review full records in the manual workflow.</p>}
        </li>;
      })}</ol>
      {!result.run.observations.length&&<p className="mt-3 text-sm text-[#9bb3a6]">No tools have been observed for this run.</p>}
      <details className="mt-4 break-all text-xs text-[#9bb3a6]"><summary className="cursor-pointer py-2">Investigation references</summary><p>Run: {result.run.run_id}</p><p>Conversation: {result.run.conversation_id}</p><p>Initial trace: {result.run.initial_request_id}</p><p>Revision: {result.run.revision}</p>{trace&&<p>Current trace: {trace}</p>}</details>
      {!result.run.can_cancel&&<button type="button" disabled={busy} onClick={nextQuestion} className={control+" mt-4"}>Ask another investigation question</button>}
    </article>}
  </div>;
}
