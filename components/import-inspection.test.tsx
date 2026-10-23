import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ImportInspection } from "@/components/import-inspection";

describe("import inspection", () => {
  it("requires reading work state before offering a restart", () => {
    const html=renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><ImportInspection jobId="job-1" onUpdated={async()=>{}} /></QueryClientProvider>);
    expect(html).toContain("Inspect run");
    expect(html).not.toContain("Restart failed import");
  });
});
