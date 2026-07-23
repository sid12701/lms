/**
 * Operational alerts per UI pages.md "Operational Alerts".
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
  "WEBHOOK_DELIVERY",
  "REPORT_REQUEST",
  "SYSTEM",
]);
export type AlertSubjectType = z.infer<typeof AlertSubjectType>;

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
