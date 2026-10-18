import { proxyToBackend } from "@/lib/backend-proxy";

export async function POST(request: Request, context: { params: Promise<{ jobId: string }> }) {
  const { jobId } = await context.params;
  return proxyToBackend(request, `/api/ops/imports/${encodeURIComponent(jobId)}/restart`, { operations: true });
}
