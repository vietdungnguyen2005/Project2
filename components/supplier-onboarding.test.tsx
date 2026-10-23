import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { SupplierOnboarding } from "@/components/supplier-onboarding";

describe("supplier onboarding", () => {
  it("starts with a read-only preview step and no enabled confirmation", () => {
    const html=renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><SupplierOnboarding onImported={async()=>{}} /></QueryClientProvider>);
    expect(html).toContain("Supplier onboarding");
    expect(html).toContain("Preview &amp; validate");
    expect(html).not.toContain("Confirm &amp; queue");
    expect(html).toContain("Synthetic suppliers");
  });
});
