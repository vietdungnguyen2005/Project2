import { proxyToBackend } from "@/lib/backend-proxy";

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/ops/reconciliations", { operations: true });
}

export async function POST(request: Request) {
  return proxyToBackend(request, "/api/ops/reconciliations", { operations: true });
}
