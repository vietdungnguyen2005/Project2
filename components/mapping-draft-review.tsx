"use client";

import { useEffect,useRef,useState } from "react";
import { ProfileDraftClient,suggestMapping } from "@/lib/ai-mapping/client";
import { Definition,ProfileSummary,type DefinitionInput,type MappingViewValue,type ProfileValue } from "@/lib/ai-mapping/spec";
import { loadIntegrationProfiles,type IntegrationProfile } from "@/lib/operations-api";

const input="min-h-11 w-full border border-white/20 bg-[#0b0f0e] px-3 py-2 text-sm text-white focus:border-[#2de1c2] disabled:opacity-50";
const button="min-h-11 border border-[#2de1c2]/50 px-4 py-2 text-sm font-bold text-[#7ef5df] hover:bg-[#2de1c2]/10 disabled:opacity-40";
const targets=["sku","name","on_hand"] as const;
type Props={source?:IntegrationProfile;disabled:boolean;filename:string;original:()=>ArrayBuffer;
  onBusy:(value:boolean)=>void;onAdopt:(id:string,version:number,raw:ArrayBuffer,filename:string)=>Promise<void>};

export function MappingDraftReview({source,disabled,filename,original,onBusy,onAdopt}:Props){
  const [mapping,setMapping]=useState(()=>({...source?.mapping}));
  const [id,setId]=useState(""),[version,setVersion]=useState(1);
  const [normalization,setNormalization]=useState<"TRIM"|"TRIM_UPPER">(source?.skuNormalization??"TRIM");
  const [method,setMethod]=useState<"rule"|"ai">("rule"),[reviewed,setReviewed]=useState(false);
  const [draft,setDraft]=useState<MappingViewValue|null>(null),[snapshot,setSnapshot]=useState<ArrayBuffer|null>(null);
  const [saved,setSaved]=useState<ProfileValue|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState("");
  const [uncertain,setUncertain]=useState(false),[attempt,setAttempt]=useState<DefinitionInput|null>(null);
  const [inspected,setInspected]=useState<{match:ProfileValue|null}|null>(null);
  const controller=useRef<AbortController|null>(null);
  useEffect(()=>()=>{controller.current?.abort();onBusy(false);},[onBusy]);
  const definition=Definition.safeParse({profileId:id,version,encoding:source?.encoding,vendorCode:source?.vendorCode,mapping,skuNormalization:normalization});
  const locked=disabled||busy||uncertain;
  const begin=()=>{setBusy(true);onBusy(true);setError("");};
  const finish=()=>{setBusy(false);onBusy(false);};
  const suggest=async()=>{
    if(!source)return;begin();controller.current=new AbortController();
    try{
      const raw=original();setSnapshot(raw);
      const bytes=new Uint8Array(raw);let binary="";
      for(let start=0;start<bytes.length;start+=32768)binary+=String.fromCharCode(...bytes.subarray(start,start+32768));
      const result=await suggestMapping({raw_base64:btoa(binary),declared_encoding:source.encoding,source_profile_id:source.profileId,source_profile_version:source.version,method,synthetic:true},controller.current.signal);
      if(!result.success){setError("Draft unavailable. Review and enter the source columns manually, or try again explicitly.");return;}
      setDraft(result.data);setReviewed(false);setMapping(Object.fromEntries(result.data.draft.assignments.map(a=>[a.target_field,a.source_header])));
    }catch{setError("Select an original file with the declared encoding before requesting a draft.");}
    finally{finish();}
  };
  const publish=async()=>{
    if(!reviewed||!definition.success)return;begin();setAttempt(definition.data);
    try{
      const raw=snapshot??original();setSnapshot(raw);
      const result=await new ProfileDraftClient().publish(definition.data);
      if(result.success){setSaved(result.data);setReviewed(false);}
      else if(result.code==="INPUT_INVALID")setError("Review the profile definition and exact source column names.");
      else{setUncertain(true);setError("Creation outcome requires inspection. Do not submit another version blindly.");}
    }catch{setError("Original bytes are unavailable. Select the original file first.");}
    finally{finish();}
  };
  const activate=async()=>{
    if(!saved||!snapshot)return;begin();
    const result=await new ProfileDraftClient().activate(saved);
    if(result.success){setSaved(result.data);try{await onAdopt(result.data.profileId,result.data.version,snapshot,filename);}catch{setError("Activation was acknowledged. Reload profiles and select this active version before previewing.");}}
    else{setUncertain(true);setError("Activation outcome requires inspection. Do not retry automatically.");}
    finish();
  };
  const inspect=async()=>{
    begin();setInspected(null);
    try{
      const profiles=await loadIntegrationProfiles();
      const found=profiles.map(p=>ProfileSummary.safeParse(p)).filter(p=>p.success).map(p=>p.data).find(p=>p.profileId===attempt?.profileId&&p.version===attempt?.version);
      const {active:_active,...fields}=found??{};
      setInspected({match:found&&JSON.stringify(fields)===JSON.stringify(attempt)?found:null});
    }catch{setError("Profile inspection failed. Sending remains locked.");}
    finally{finish();}
  };
  return <section aria-labelledby="mapping-draft-heading" className="mt-5 border border-[#2de1c2]/30 bg-black/20 p-4 sm:p-5">
    <p className="font-mono text-[10px] uppercase tracking-[0.18em] text-[#2de1c2]">01 / Suggest → Review → Save inactive → Activate</p>
    <h3 id="mapping-draft-heading" className="mt-2 text-lg font-bold">Mapping draft / human review</h3>
    <p className="mt-2 text-sm leading-6 text-[#9bb3a6]">Synthetic suppliers only. Suggestions can be wrong. You own the edits and approval; Java validates the profile and file again. No import runs until a separate preview and confirmation.</p>
    {!source?<p className="mt-3 text-sm text-amber-200">Select an active source profile first.</p>:<>
      <p className="mt-3 font-mono text-xs text-[#c6d7cd]">Source {source.profileId} / v{source.version} · vendor {source.vendorCode} · declared {source.encoding}</p>
      <div className="mt-4 flex flex-wrap items-end gap-3">
        <label className="grid gap-1 text-sm">Draft method<select className={input} value={method} disabled={locked||!!saved} onChange={e=>setMethod(e.target.value as "rule"|"ai")}><option value="rule">Frozen header rules</option><option value="ai">Local AI with SOP context</option></select></label>
        <button type="button" className={button} disabled={locked||!!saved} onClick={()=>void suggest()}>Suggest mapping</button>
      </div>
      {draft?<div className="mt-4 border-l-2 border-amber-300/60 pl-3 text-xs leading-6 text-amber-100">
        <p>{draft.draft.status} · {draft.draft.provider_mode} · original SHA256 <span className="break-all">{draft.draft.raw_sha256}</span></p>
        <p>{draft.draft.warnings.join(" · ")}</p>
        {draft.evidence.length?draft.evidence.map(e=><blockquote key={e.citation.chunk_id} className="mt-2 whitespace-pre-wrap break-words border-t border-white/15 pt-2">SOP {e.citation.document_id} / {e.citation.locator.kind} {e.citation.locator.value}: {e.excerpt}</blockquote>):<p>No linked SOP excerpt. Rule matches and model guesses are not approval.</p>}
      </div>:null}
      <fieldset disabled={locked||!!saved} className="mt-4 grid gap-4 disabled:opacity-60">
        <div className="grid gap-3 md:grid-cols-3">{targets.map(target=><label key={target} className="grid gap-2 text-sm"><span>Source column for {target}</span>
          <input aria-label={`Source column for ${target}`} className={input} value={mapping[target]??""} maxLength={80} onChange={e=>{setMapping({...mapping,[target]:e.target.value});setReviewed(false);}}/>
          <span className="break-words text-xs text-[#9bb3a6]">Current: {source.mapping[target]} → edited: {mapping[target]||"missing"}</span>
          {draft?.draft.assignments.filter(a=>a.target_field===target).map(a=><span key={target} className="text-xs leading-5 text-amber-100">Hint: {a.source_header} · {a.basis} · uncertainty {a.uncertainty}. {a.reason}</span>)}
        </label>)}</div>
        <div className="grid gap-3 sm:grid-cols-3">
          <label className="grid gap-2 text-sm">New profile ID<input className={input} value={id} maxLength={48} onChange={e=>{setId(e.target.value);setReviewed(false);}}/></label>
          <label className="grid gap-2 text-sm">New profile version<input className={input} type="number" min={1} max={2147483647} value={version} onChange={e=>{setVersion(Number(e.target.value));setReviewed(false);}}/></label>
          <label className="grid gap-2 text-sm">SKU normalization<select className={input} value={normalization} onChange={e=>{setNormalization(e.target.value as "TRIM"|"TRIM_UPPER");setReviewed(false);}}><option>TRIM</option><option>TRIM_UPPER</option></select></label>
        </div>
        <label className="flex items-start gap-3 text-sm leading-6"><input type="checkbox" className="mt-1 size-5 accent-[#2de1c2]" checked={reviewed} onChange={e=>setReviewed(e.target.checked)}/>I reviewed the exact source columns, vendor, encoding and new immutable profile version. Create it inactive.</label>
      </fieldset>
      {!saved?<button type="button" className={`${button} mt-4`} disabled={locked||!reviewed||!definition.success} onClick={()=>void publish()}>Create inactive profile</button>:<div className="mt-4 grid gap-3 text-sm text-[#7ef5df]">
        <p>Saved {saved.profileId} / v{saved.version} · {saved.active?"active":"inactive"}. No import submitted.</p>
        {!saved.active?<button type="button" className={button} disabled={locked} onClick={()=>void activate()}>Activate saved profile</button>:<button type="button" className={button} disabled={locked||!snapshot} onClick={()=>snapshot&&void onAdopt(saved.profileId,saved.version,snapshot,filename)}>Use active profile for preview</button>}
      </div>}
      {uncertain?<div className="mt-4 grid gap-3 border border-amber-300/40 p-3 text-sm text-amber-100">
        <button type="button" className={button} disabled={busy||disabled} onClick={()=>void inspect()}>Inspect saved profiles</button>
        {inspected?<><p>{inspected.match?`Matching version found: ${inspected.match.active?"active":"inactive"}.`:"No exact definition found in the latest profiles. Absence does not prove the operation failed; inspect the profile/version before choosing a new immutable version."}</p><button type="button" className={button} onClick={()=>{setSaved(inspected.match);setUncertain(false);setInspected(null);setReviewed(false);setError("");}}>I inspected the profile outcome</button></>:null}
      </div>:null}
    </>}
    {error?<p role="alert" className="mt-3 text-sm text-amber-200">{error}</p>:null}
  </section>;
}
