import type { Metadata } from "next";
import { OperationsConsole } from "@/components/operations-console";
import { OperatorAccess } from "@/components/operator-access";

export const metadata: Metadata = {
  title: "Migration Operations",
  description: "Restartable legacy commerce import and reconciliation evidence.",
};
export const dynamic="force-dynamic";

export default function OperationsPage() {
  return <OperatorAccess><OperationsConsole mappingEnabled={process.env.AI_COPILOT_ENABLED==="true"&&process.env.AI_MAPPING_DRAFTS_ENABLED==="true"} /></OperatorAccess>;
}
