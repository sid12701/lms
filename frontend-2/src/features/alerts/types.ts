/**
 * View-layer types for the `/alerts` surface.
 *
 * The wire-format alert is `OperationalAlert` (Zod schema in
 * `src/schemas/alert.ts`). The page renders a denormalised row that already
 * carries the actor display name + relative-time pre-format hint so the
 * table component stays pure.
 */
import { z } from "zod";
import { AlertSeverity, AlertStatus, AlertSubjectType, OperationalAlert } from "@/schemas/alert";

export type { OperationalAlert };
export { AlertSeverity, AlertStatus, AlertSubjectType };

/**
 * Server-side filters consumed by the mock handler. Mirrors the
 * `OperationalAlert` Zod shape but every field is optional + paginated.
 */
export const AlertsListFilters = z.object({
  /** Open / Acknowledged / undefined (=> both). */
  status: AlertStatus.optional(),
  /** Multi-select of severities. */
  severity: z.array(AlertSeverity).optional(),
  /** Optional subject-type narrowing. */
  subjectType: AlertSubjectType.optional(),
  /** Free-text search across title + message. */
  q: z.string().trim().min(1).max(120).optional(),
  page: z.coerce.number().int().min(0).optional(),
  pageSize: z.coerce.number().int().min(5).max(100).optional(),
});
export type AlertsListFilters = z.infer<typeof AlertsListFilters>;

/**
 * Row projection used by the list table — the wire alert plus the
 * `acknowledgedByName` lookup the handler resolves from `db.users`.
 */
export interface AlertRow extends OperationalAlert {
  acknowledgedByName: string | null;
}

export interface AlertsListResponse {
  items: AlertRow[];
  total: number;
  page: number;
  pageSize: number;
}

export interface AcknowledgeAlertInput {
  note: string | null;
  idempotencyKey: string;
}

export interface AcknowledgeAlertResponse {
  alert: AlertRow;
}

export interface EscalateAlertInput {
  subjectType: AlertSubjectType;
  subjectId: string | null;
  title: string;
  message: string;
  idempotencyKey: string;
}

export interface EscalateAlertResponse {
  alert: AlertRow;
}

/** Configured alert rule (Follow-up #2). */
export interface AlertRuleRow {
  id: string;
  code: string;
  name: string;
  description: string;
  enabled: boolean;
  audience: "OPS" | "SYSTEM_ADMIN";
  triggerKind: "SCHEDULED" | "EVENT";
  configJson: string | null;
  lastEvaluatedAt: string | null;
}
