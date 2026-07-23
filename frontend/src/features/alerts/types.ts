/**
 * View-layer types for the `/alerts` surface.
 *
 * The wire-format alert is `OperationalAlert` (Zod schema in
 * `src/schemas/alert.ts`). The page renders a denormalised row that already
 * carries the actor display name + relative-time pre-format hint so the
 * table component stays pure.
 */
import { z } from "zod";
import { AlertSeverity, AlertStatus, AlertSubjectType } from "@/schemas/alert";
import type { OperationalAlert } from "@/schemas/alert";
import { ADMIN_LIST_FILTER_FIELDS } from "@/lib/admin-list-url-state";

export type { OperationalAlert };

/**
 * Server-side filters consumed by the backend. Mirrors the
 * `OperationalAlert` Zod shape but every field is optional + paginated.
 */
const AlertsListFilters = z.object({
  /** Open / Acknowledged / undefined (=> both). */
  status: AlertStatus.optional(),
  /** Multi-select of severities. */
  severity: z.array(AlertSeverity).optional(),
  /** Optional subject-type narrowing. */
  subjectType: AlertSubjectType.optional(),
  /** Free-text search across title + message. */
  ...ADMIN_LIST_FILTER_FIELDS,
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
