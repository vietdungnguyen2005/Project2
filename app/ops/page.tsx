import type { Metadata } from "next";
import { OperationsConsole } from "@/components/operations-console";

export const metadata: Metadata = {
  title: "Migration Operations",
  description: "Restartable legacy commerce import and reconciliation evidence.",
};

export default function OperationsPage() {
  return <OperationsConsole />;
}
