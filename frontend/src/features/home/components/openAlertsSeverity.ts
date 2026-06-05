import type { AlertSeverity } from "@/schemas/alert";

export type OpenAlertsSeverityToken = "danger" | "warning" | "info" | "neutral";

export const SEVERITY_TOKEN: Record<AlertSeverity, OpenAlertsSeverityToken> = {
  CRITICAL: "danger",
  HIGH: "warning",
  MEDIUM: "info",
  LOW: "neutral",
};
