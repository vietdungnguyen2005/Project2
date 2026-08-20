import { proxyToBackend } from "@/lib/backend-proxy";

export async function GET(request: Request) {
  return proxyToBackend(request, "/api/ops/imports", { operations: true });
}

export async function POST(request: Request) {
  return proxyToBackend(request, "/api/ops/imports", { operations: true });
}
