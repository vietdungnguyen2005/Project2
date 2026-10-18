import { proxyToBackend } from "@/lib/backend-proxy";

type Context = { params: Promise<{ action: string }> };

async function forward(request: Request, context: Context) {
  const { action } = await context.params;
  const method = request.method;
  if (!((action === "session" && method === "GET") ||
    ((action === "login" || action === "logout") && method === "POST"))) {
    return Response.json({ code: "ROUTE_NOT_ALLOWED" }, { status: 404 });
  }
  return proxyToBackend(request, `/api/auth/${action}`);
}

export const GET = forward;
export const POST = forward;
