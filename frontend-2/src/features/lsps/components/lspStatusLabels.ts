import type { LspOperationalStatus, LspStatusChangeReason } from "@/schemas/lsp";

export const LSP_STATUS_CHANGE_REASON_OPTIONS: readonly {
  value: LspStatusChangeReason;
  label: string;
  description: string;
}[] = [
  {
    value: "SECURITY_INCIDENT",
    label: "Security incident",
    description: "Credential compromise, abuse, or other security event.",
  },
  {
    value: "COMPLIANCE",
    label: "Compliance",
    description: "Regulatory or policy-driven suspension.",
  },
  {
    value: "OFFBOARDING",
    label: "Offboarding",
    description: "Partner contract ended or tenant removed from the platform.",
  },
  {
    value: "OPERATIONAL",
    label: "Operational",
    description: "Routine operational change (maintenance, testing, etc.).",
  },
];

export const LSP_OPERATIONAL_STATUS_OPTIONS: readonly {
  value: LspOperationalStatus;
  label: string;
}[] = [
  { value: "ACTIVE", label: "Active" },
  { value: "INACTIVE", label: "Inactive (disabled)" },
];

export function lspStatusChangeActionLabel(
  current: LspOperationalStatus,
  target: LspOperationalStatus,
): string {
  if (current === target) return "Save";
  if (target === "INACTIVE") return "Disable LSP";
  return "Reactivate LSP";
}
