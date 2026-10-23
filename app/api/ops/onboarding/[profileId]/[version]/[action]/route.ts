import { proxyToBackend } from "@/lib/backend-proxy";

export async function POST(request: Request, context: {params:Promise<{profileId:string;version:string;action:string}>}) {
  const {profileId,version,action}=await context.params;
  if (!/^[a-z][a-z0-9-]{0,47}$/.test(profileId) || !/^[1-9][0-9]{0,9}$/.test(version) || Number(version)>2_147_483_647
    || !["preview","confirm"].includes(action)) return new Response(null,{status:400});
  return proxyToBackend(request,`/api/ops/onboarding/${profileId}/${version}/${action}`,{operations:true});
}
