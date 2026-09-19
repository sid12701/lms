/**
 * Alerts inbox, wired to the live backend.
 *
 * Backend contract: `OpsAlertController` under `/api/v1/internal/alerts`
 * (SYSTEM_ADMIN + OPS_USER). Per `docs/gap-fixes.md` § Gap #15, the
 * acknowledge endpoint accepts an optional `note` (max 500 chars) and
 * round-trips it on the response.
 */
import { requestJson, requestJsonWithHeaders, buildQueryPath } from "@/lib/api/http-client";
import { readPaginationHeaders } from "@/lib/api/pagination-headers";
import type {
  AlertRow,
  AlertRuleRow,
  AlertsListFilters,
  AlertsListResponse,
  AcknowledgeAlertInput,
  AcknowledgeAlertResponse,
  EscalateAlertInput,
  EscalateAlertResponse,
} from "./types";
import type { AlertStatus } from "@/schemas/alert";
import { apiAlertSeverity, apiAlertSubjectType } from "@/schemas/alert";

const BASE = "/api/v1/internal/alerts";

function backendStatus(value: AlertStatus | undefined): string | undefined {
  if (!value) return undefined;
  return value === "OPEN" ? "NEW" : value;
}

function frontendStatus(value: string): AlertStatus {
  return value === "ACKNOWLEDGED" ? "ACKNOWLEDGED" : "OPEN";
}

interface BackendAlertResponse {
  id: string;
  type: string;
  severity: string;
  status: string;
  title: string;
  message: string;
  subjectType: string;
  subjectId: string | null;
  correlationId: string;
  contextJson: string | null;
  createdAt: string;
  acknowledgedAt: string | null;
  acknowledgedByUsername: string | null;
  acknowledgementNote: string | null;
}

function toAlertRow(payload: BackendAlertResponse): AlertRow {
  return {
    id: payload.id,
    type: payload.type,
    // H28 — unknown severities/subjects stay visible as UNKNOWN:<raw>.
    severity: apiAlertSeverity(payload.severity),
    status: frontendStatus(payload.status),
    title: payload.title,
    message: payload.message,
    subjectType: apiAlertSubjectType(payload.subjectType),
    // H28 — a null subject id means "no subject", not a subject literally
    // called "unknown"; a missing correlation id stays missing, never a
    // fabricated zero UUID.
    subjectId: payload.subjectId ?? null,
    correlationId: payload.correlationId?.trim() ? payload.correlationId : null,
    contextJson: payload.contextJson ?? undefined,
    createdAt: payload.createdAt,
    acknowledgedAt: payload.acknowledgedAt,
    acknowledgedBy: null,
    acknowledgmentNote: payload.acknowledgementNote ?? null,
    acknowledgedByName: payload.acknowledgedByUsername ?? null,
  };
}

/**
 * H30 — the inbox is server-paginated and server-filtered. Every filter
 * (status, severities, subject type, text) travels to the backend, which
 * applies it to the full dataset before paginating with stable ordering; the
 * total comes from the pagination headers. Filtering/paginating only the
 * first fetched page locally hid older matching alerts and understated
 * totals, so the local path is gone.
 */
export async function listAlerts(filters: AlertsListFilters = {}): Promise<AlertsListResponse> {
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;
  const path = buildQueryPath(BASE, {
    status: backendStatus(filters.status),
    severity: filters.severity && filters.severity.length > 0 ? [...filters.severity] : undefined,
    subjectType: filters.subjectType,
    q: filters.q,
    offset: page * pageSize,
    limit: pageSize,
    paginationDetails: "ON",
  });
  const { data, headers } = await requestJsonWithHeaders<BackendAlertResponse[]>(path);
  const pagination = readPaginationHeaders(headers);
  return {
    items: data.map(toAlertRow),
    total: pagination.totalCount ?? data.length,
    page,
    pageSize,
  };
}

export async function acknowledgeAlert(
  id: string,
  input: AcknowledgeAlertInput,
): Promise<AcknowledgeAlertResponse> {
  const body: { note?: string } = {};
  if (input.note && input.note.trim().length > 0) {
    body.note = input.note.trim();
  }
  const payload = await requestJson<BackendAlertResponse>(
    `${BASE}/${id}/acknowledge`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return { alert: toAlertRow(payload) };
}

/**
 * OPS_USER escalation surface (Gap #16). Creates a high-severity
 * `OPS_USER_ESCALATION` alert that SYSTEM_ADMIN sees in the alerts inbox.
 * Used from the loan-detail page when ops needs admin to intervene on a
 * loan that is stuck or otherwise needs an out-of-band review.
 */
export async function escalateAlert(input: EscalateAlertInput): Promise<EscalateAlertResponse> {
  const body = {
    subjectType: input.subjectType,
    subjectId: input.subjectId,
    title: input.title.trim(),
    message: input.message.trim(),
  };
  const payload = await requestJson<BackendAlertResponse>(
    `${BASE}/escalate`,
    {
      method: "POST",
      body: JSON.stringify(body),
    },
    { idempotencyKey: input.idempotencyKey },
  );
  return { alert: toAlertRow(payload) };
}

interface BackendAlertRuleResponse {
  id: string;
  code: string;
  name: string;
  description: string;
  enabled: boolean;
  audience: string;
  triggerKind: string;
  configJson: string | null;
  lastEvaluatedAt: string | null;
}

function toAlertRuleRow(payload: BackendAlertRuleResponse): AlertRuleRow {
  return {
    id: payload.id,
    code: payload.code,
    name: payload.name,
    description: payload.description,
    enabled: payload.enabled,
    audience: payload.audience === "OPS" ? "OPS" : "SYSTEM_ADMIN",
    triggerKind: payload.triggerKind === "EVENT" ? "EVENT" : "SCHEDULED",
    configJson: payload.configJson,
    lastEvaluatedAt: payload.lastEvaluatedAt,
  };
}

/** SYSTEM_ADMIN-only catalogue of configured alert rules. */
export async function listAlertRules(): Promise<AlertRuleRow[]> {
  const payload = await requestJson<BackendAlertRuleResponse[]>(`${BASE}/rules`);
  return payload.map(toAlertRuleRow);
}
