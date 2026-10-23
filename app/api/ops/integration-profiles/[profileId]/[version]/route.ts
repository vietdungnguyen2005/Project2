import { proxyToBackend } from "@/lib/backend-proxy";

export async function PATCH(request: Request, context: {params:Promise<{profileId:string;version:string}>}) {
  const {profileId,version}=await context.params;
  if (!/^[a-z][a-z0-9-]{0,47}$/.test(profileId) || !/^[1-9][0-9]{0,9}$/.test(version) || Number(version)>2_147_483_647) return new Response(null,{status:400});
  return proxyToBackend(request,`/api/ops/integration-profiles/${profileId}/${version}`,{operations:true});
}
