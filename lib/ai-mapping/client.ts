import { operatorFetch } from "../operator-session";
import { Definition,ProfileSummary,MappingInput,MappingView,type MappingViewValue,type DefinitionInput,type ProfileValue,type ClientResult,type ProfileDraftSpec } from "./spec";
import type { z } from "zod";

export class ProfileDraftClient implements ProfileDraftSpec {
  async activate(profile:ProfileValue):Promise<ClientResult<ProfileValue>>{
    const parsed=ProfileSummary.safeParse(profile);if(!parsed.success||parsed.data.active)return {success:false,code:"INPUT_INVALID"};
    try{
      const response=await operatorFetch(`/api/ops/integration-profiles/${encodeURIComponent(profile.profileId)}/${profile.version}`,{method:"PATCH",headers:{"Content-Type":"application/json"},body:JSON.stringify({active:true})});
      if(!response.ok)return {success:false,code:"OUTCOME_UNKNOWN"};
      const value=ProfileSummary.safeParse(await response.json());
      if(!value.success||JSON.stringify(value.data)!==JSON.stringify({...parsed.data,active:true}))return {success:false,code:"OUTPUT_INVALID"};
      return {success:true,data:value.data};
    }catch{return {success:false,code:"OUTCOME_UNKNOWN"};}
  }
  async publish(definition:DefinitionInput):Promise<ClientResult<ProfileValue>>{
    const input=Definition.safeParse(definition);if(!input.success)return {success:false,code:"INPUT_INVALID"};
    try{
      const response=await operatorFetch("/api/ops/integration-profiles",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(input.data)});
      if(!response.ok)return {success:false,code:"OUTCOME_UNKNOWN"};
      const value=ProfileSummary.safeParse(await response.json());
      if(!value.success)return {success:false,code:"OUTPUT_INVALID"};
      const {active,...saved}=value.data;
      if(active||JSON.stringify(saved)!==JSON.stringify(input.data))return {success:false,code:"OUTPUT_INVALID"};
      return {success:true,data:value.data};
    }catch{return {success:false,code:"OUTCOME_UNKNOWN"};}
  }
}

export async function suggestMapping(input:z.infer<typeof MappingInput>,signal:AbortSignal):Promise<ClientResult<MappingViewValue>>{
  const parsed=MappingInput.safeParse(input);if(!parsed.success)return {success:false,code:"INPUT_INVALID"};
  try{
    const response=await operatorFetch("/api/ops/ai/mapping/suggest",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(parsed.data),signal});
    if(!response.ok)return {success:false,code:"REQUEST_FAILED"};
    const value=MappingView.safeParse(await response.json());
    return value.success?{success:true,data:value.data}:{success:false,code:"OUTPUT_INVALID"};
  }catch{return {success:false,code:"REQUEST_FAILED"};}
}
