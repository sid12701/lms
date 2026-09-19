/**
 * Operational alerts, surfaced on `/alerts` (`@/features/alerts`).
 */
import { z } from "zod";
import { Iso8601, Uuid } from "./common";

export const AlertSeverity = z.enum(["CRITICAL", "HIGH", "MEDIUM", "LOW"]);
export type AlertSeverity = z.infer<typeof AlertSeverity>;

export const AlertStatus = z.enum(["OPEN", "ACKNOWLEDGED"]);
export type AlertStatus = z.infer<typeof AlertStatus>;

export const AlertSubjectType = z.enum([
  "LOAN_APPLICATION",
  "LOAN_ACCOUNT",
  "BORROWER",
  "REPORT_REQUEST",
  "SYSTEM",
]);
export type AlertSubjectType = z.infer<typeof AlertSubjectType>;

/**
 * H28 — unknown/absent alert metadata is modelled explicitly, mirroring the
 * loan-status `UNKNOWN:<raw>` pattern. A backend value outside the known
 * vocabulary (or a legacy row the backend enum no longer names) must stay
 * visible as unknown — never fold into MEDIUM/SYSTEM, which read as measured
 * triage facts.
 */
export type AlertSeverityOrUnknown = AlertSeverity | `UNKNOWN:${string}`;
export type AlertSubjectTypeOrUnknown = AlertSubjectType | `UNKNOWN:${string}`;

/** Map a wire severity without folding unknown values into MEDIUM. */
export function apiAlertSeverity(raw: string | null | undefined): AlertSeverityOrUnknown {
  const trimmed = raw?.trim() ?? "";
  const parsed = AlertSeverity.safeParse(trimmed);
  return parsed.success ? parsed.data : `UNKNOWN:${trimmed}`;
}

/** Map a wire subject type without folding unknown values into SYSTEM. */
export function apiAlertSubjectType(raw: string | null | undefined): AlertSubjectTypeOrUnknown {
  const trimmed = raw?.trim() ?? "";
  const parsed = AlertSubjectType.safeParse(trimmed);
  return parsed.success ? parsed.data : `UNKNOWN:${trimmed}`;
}

/** Label for an unknown severity/subject raw value (drift stays visible). */
export function unknownAlertValueLabel(raw: string): string {
  const trimmed = raw.trim();
  return trimmed ? `Unknown (${trimmed})` : "Unknown";
}

const OperationalAlert = z.object({
  id: Uuid,
  type: z.string().min(1).max(80),
  severity: AlertSeverity,
  status: AlertStatus,
  title: z.string().min(1).max(160),
  message: z.string().min(1).max(1000),
  subjectType: AlertSubjectType,
  subjectId: z.string().min(1).max(80),
  correlationId: Uuid,
  /** Free-form structured context; opaque object per UI page. */
  contextJson: z.union([z.record(z.unknown()), z.string()]).optional(),
  createdAt: Iso8601,
  acknowledgedAt: Iso8601.nullable(),
  acknowledgedBy: Uuid.nullable(),
  acknowledgmentNote: z.string().max(500).nullable(),
});
export type OperationalAlert = z.infer<typeof OperationalAlert>;
