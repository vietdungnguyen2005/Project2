import type { Metadata } from "next";
import { OperationsConsole } from "@/components/operations-console";
import { OperatorAccess } from "@/components/operator-access";

export const metadata: Metadata = {
  title: "Migration Operations",
  description: "Restartable legacy commerce import and reconciliation evidence.",
};
export const dynamic="force-dynamic";

export default function OperationsPage() {
  const enabled=process.env.AI_COPILOT_ENABLED==="true";
  return <OperatorAccess><OperationsConsole mappingEnabled={enabled&&process.env.AI_MAPPING_DRAFTS_ENABLED==="true"}
    copilotEnabled={enabled&&process.env.AI_COPILOT_PANEL_ENABLED==="true"}
    qaEnabled={enabled&&process.env.AI_QA_ENABLED==="true"} runsEnabled={enabled&&process.env.AI_RUNS_ENABLED==="true"}/></OperatorAccess>;
}
