import type { AlertSubjectType } from "@/schemas/alert";

function parseContextJson(contextJson: unknown): Record<string, unknown> | null {
  if (!contextJson) return null;
  if (typeof contextJson === "string") {
    try {
      const parsed = JSON.parse(contextJson) as unknown;
      return parsed && typeof parsed === "object" && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : null;
    } catch {
      return null;
    }
  }
  if (typeof contextJson === "object" && !Array.isArray(contextJson)) {
    return contextJson as Record<string, unknown>;
  }
  return null;
}

function readApplicationIdFromContext(contextJson: unknown): string | null {
  const context = parseContextJson(contextJson);
  const value = context?.applicationId;
  return typeof value === "string" && value.length > 0 ? value : null;
}

/** Deep-link target for an operational alert row. */
export function resolveAlertSubjectHref(
  subjectType: AlertSubjectType,
  subjectId: string,
  correlationId: string,
  contextJson?: unknown,
): string {
  switch (subjectType) {
    case "LOAN_APPLICATION":
      return `/loan-applications/${subjectId}`;
    case "LOAN_ACCOUNT": {
      const applicationId = readApplicationIdFromContext(contextJson);
      if (applicationId) return `/loan-applications/${applicationId}`;
      return `/loan-applications/${subjectId}`;
    }
    case "BORROWER":
      return `/borrowers/${subjectId}`;
    case "REPORT_REQUEST":
    case "SYSTEM":
    default:
      return `/audit?correlationId=${encodeURIComponent(correlationId)}`;
  }
}
