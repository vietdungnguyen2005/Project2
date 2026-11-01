import { expect,it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { OperatorCopilot } from "../components/operator-copilot";
import { QueryClient,QueryClientProvider } from "@tanstack/react-query";

it("keeps the manual workflow reachable when both Copilot capabilities are disabled",()=>{
  const html=renderToStaticMarkup(<OperatorCopilot qaEnabled={false} runsEnabled={false}/>);
  expect(html).toContain("Operator Copilot");
  expect(html).toContain('href="#supplier-heading"');
  expect(html).toContain("Policy Q&amp;A is disabled");
  expect(html).not.toContain("API key");
});

it("labels the policy question and selects an actual supplier profile rather than browser authority",()=>{
  const html=renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><OperatorCopilot qaEnabled runsEnabled={false}/></QueryClientProvider>);
  expect(html).toContain("Supplier policy profile");expect(html).toContain("Policy question");
  expect(html).toContain("Ask with sources");expect(html).not.toContain("Tenant");
});

it("offers a read-only investigation request with explicit run inspection and manual handoff",()=>{
  const html=renderToStaticMarkup(<OperatorCopilot qaEnabled={false} runsEnabled/>);
  expect(html).toContain("Investigation question");expect(html).toContain("Start investigation");
  expect(html).toContain("Inspect saved run");expect(html).toContain("Read-only tools");
});
