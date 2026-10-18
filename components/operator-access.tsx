"use client";

import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { getOperatorSession, type OperatorSession } from "@/lib/operator-session";

export function OperatorAccess({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<OperatorSession | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const queryClient = useQueryClient();

  useEffect(() => {
    const controller = new AbortController();
    const refresh = () => {
      void getOperatorSession(controller.signal).then(setSession).catch((cause: unknown) => {
        if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : "Session unavailable.");
      });
    };
    refresh();
    const timer = window.setInterval(refresh, 60_000);
    window.addEventListener("focus", refresh);
    return () => { controller.abort(); window.clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, []);

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true); setError("");
    try {
      const current = await getOperatorSession();
      const response = await fetch("/api/auth/login", {
        method: "POST", headers: { "X-CSRF-TOKEN": current.csrfToken },
        body: new URLSearchParams({ username: String(data.get("username")), password: String(data.get("password")) }),
      });
      if (!response.ok) throw new Error(response.status === 429 ? "Too many sign-in attempts. Try again in a minute." : "Sign-in failed. Check your credentials and try again.");
      form.reset(); queryClient.clear();
      setSession(await getOperatorSession());
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Sign-in failed."); }
    finally { setBusy(false); }
  }

  async function logout() {
    setBusy(true); setError("");
    try {
      const current = await getOperatorSession();
      const response = await fetch("/api/auth/logout", { method: "POST", headers: { "X-CSRF-TOKEN": current.csrfToken } });
      if (!response.ok) throw new Error("Unable to sign out. Please try again.");
      queryClient.clear(); setSession(await getOperatorSession());
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Sign-out failed."); }
    finally { setBusy(false); }
  }

  if (session?.authenticated && session.roles.includes("ROLE_OPERATOR")) {
    return <>
      <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-950 px-5 py-3 text-sm text-white">
        <span>Signed in as {session.username}</span>
        <button type="button" disabled={busy} onClick={() => void logout()} className="rounded border border-white/40 px-4 py-2 disabled:opacity-50">Sign out</button>
        {error && <p role="alert" className="w-full text-rose-300">{error}</p>}
      </div>
      {children}
    </>;
  }
  return <main className="grid min-h-screen place-items-center bg-slate-950 px-5 py-12 text-slate-100">
    <section className="w-full max-w-md rounded-2xl border border-slate-700 bg-slate-900 p-7">
      <a href="/" className="text-sm text-cyan-300 underline">Back to storefront</a>
      <h1 className="mt-5 text-3xl font-black">Operator sign-in</h1>
      <p className="mt-3 text-sm leading-6 text-slate-300">Migration and fulfillment tools require an authorized operator account.</p>
      {session?.authenticated ? <>
        <p className="mt-5" role="status">Your account does not have operator access.</p>
        <button type="button" onClick={() => void logout()} disabled={busy} className="mt-5 rounded border px-4 py-2">Sign out</button>
      </> : <form onSubmit={(event) => void login(event)} className="mt-6 space-y-4">
        <label className="block text-sm">Username<input name="username" autoComplete="username" required maxLength={100} className="mt-2 block w-full rounded border border-slate-600 bg-slate-950 p-3" /></label>
        <label className="block text-sm">Password<input name="password" type="password" autoComplete="current-password" required maxLength={128} className="mt-2 block w-full rounded border border-slate-600 bg-slate-950 p-3" /></label>
        <button type="submit" disabled={busy || !session} className="w-full rounded bg-cyan-300 px-4 py-3 font-bold text-slate-950 disabled:opacity-50">{busy ? "Signing in…" : "Sign in"}</button>
      </form>}
      {!session && !error && <p role="status" className="mt-4 text-sm">Checking session…</p>}
      {error && <p role="alert" className="mt-4 text-sm text-rose-300">{error}</p>}
    </section>
  </main>;
}
