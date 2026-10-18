import { afterEach, expect, it, vi } from "vitest";
import { operatorFetch } from "./operator-session";

afterEach(() => vi.unstubAllGlobals());

it("fetches the current CSRF token before an operator mutation", async () => {
  const fetchMock = vi.fn()
    .mockResolvedValueOnce(Response.json({ authenticated: true, roles: ["ROLE_OPERATOR"], csrfToken: "fresh-token" }))
    .mockResolvedValueOnce(Response.json({ id: "job-1" }));
  vi.stubGlobal("fetch", fetchMock);
  const response = await operatorFetch("/api/ops/imports", {
    method: "POST", headers: { "Content-Type": "text/csv" }, body: "sku,name",
  });
  expect(fetchMock.mock.calls[0][0]).toBe("/api/auth/session");
  expect(fetchMock.mock.calls[1][0]).toBe("/api/ops/imports");
  expect(new Headers(fetchMock.mock.calls[1][1].headers).get("X-CSRF-TOKEN")).toBe("fresh-token");
  expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Content-Type")).toBe("text/csv");
  expect(response.status).toBe(200);
});
