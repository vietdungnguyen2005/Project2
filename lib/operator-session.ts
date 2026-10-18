export type OperatorSession = {
  authenticated: boolean;
  username: string;
  roles: string[];
  csrfToken: string;
};

export async function getOperatorSession(signal?: AbortSignal): Promise<OperatorSession> {
  const response = await fetch("/api/auth/session", { cache: "no-store", signal });
  if (!response.ok) throw new Error("Unable to check your session. Please try again.");
  return response.json() as Promise<OperatorSession>;
}

export async function operatorFetch(url: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes((init.method ?? "GET").toUpperCase())) {
    const session = await getOperatorSession(init.signal ?? undefined);
    if (!session.authenticated) throw new Error("Please sign in to continue.");
    headers.set("X-CSRF-TOKEN", session.csrfToken);
  }
  return fetch(url, { ...init, headers, cache: "no-store" });
}
