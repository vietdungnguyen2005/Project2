import { proxyToBackend } from "@/lib/backend-proxy";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return proxyToBackend(request, "/api/ops/orders", { operations: true });
}
