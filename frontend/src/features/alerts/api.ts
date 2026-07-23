/**
 * Alerts inbox, wired to the live backend.
 *
 * Backend contract: `OpsAlertController` under `/api/v1/internal/alerts`
 * (SYSTEM_ADMIN + OPS_USER). Per `docs/gap-fixes.md` § Gap #15, the
 * acknowledge endpoint accepts an optional `note` (max 500 chars) and
 * round-trips it on the response.
 */
import { requestJson, buildQueryPath } from "@/lib/api/http-client";
import { paginate } from "@/lib/pagination";
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
import type { AlertSeverity, AlertStatus, AlertSubjectType } from "@/schemas/alert";

const BASE = "/api/v1/internal/alerts";
const FALLBACK_UUID = "00000000-0000-4000-8000-000000000000";
const FALLBACK_SUBJECT_TYPE: AlertSubjectType = "SYSTEM";
const FALLBACK_SEVERITY: AlertSeverity = "MEDIUM";

const KNOWN_SUBJECT_TYPES = new Set<AlertSubjectType>([
  "LOAN_APPLICATION",
  "LOAN_ACCOUNT",
  "BORROWER",
  "WEBHOOK_DELIVERY",
  "REPORT_REQUEST",
  "SYSTEM",
]);

const KNOWN_SEVERITIES = new Set<AlertSeverity>(["CRITICAL", "HIGH", "MEDIUM", "LOW"]);

function backendStatus(value: AlertStatus | undefined): string | undefined {
  if (!value) return undefined;
  return value === "OPEN" ? "NEW" : value;
}

function frontendStatus(value: string): AlertStatus {
  return value === "ACKNOWLEDGED" ? "ACKNOWLEDGED" : "OPEN";
}

function frontendSubject(value: string): AlertSubjectType {
  return KNOWN_SUBJECT_TYPES.has(value as AlertSubjectType)
    ? (value as AlertSubjectType)
    : FALLBACK_SUBJECT_TYPE;
}

function frontendSeverity(value: string): AlertSeverity {
  return KNOWN_SEVERITIES.has(value as AlertSeverity)
    ? (value as AlertSeverity)
    : FALLBACK_SEVERITY;
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
    severity: frontendSeverity(payload.severity),
    status: frontendStatus(payload.status),
    title: payload.title,
    message: payload.message,
    subjectType: frontendSubject(payload.subjectType),
    subjectId: payload.subjectId ?? "unknown",
    correlationId: payload.correlationId || FALLBACK_UUID,
    contextJson: payload.contextJson ?? undefined,
    createdAt: payload.createdAt,
    acknowledgedAt: payload.acknowledgedAt,
    acknowledgedBy: null,
    acknowledgmentNote: payload.acknowledgementNote ?? null,
    acknowledgedByName: payload.acknowledgedByUsername ?? null,
  };
}

export async function listAlerts(filters: AlertsListFilters = {}): Promise<AlertsListResponse> {
  const path = buildQueryPath(BASE, {
    status: backendStatus(filters.status),
  });
  const all = await requestJson<BackendAlertResponse[]>(path);
  const selectedSeverities = new Set(filters.severity ?? []);
  const filtered = all.filter((row) => {
    if (selectedSeverities.size > 0) {
      if (!selectedSeverities.has(frontendSeverity(row.severity))) return false;
    }
    if (filters.subjectType && frontendSubject(row.subjectType) !== filters.subjectType) {
      return false;
    }
    if (filters.q) {
      const needle = filters.q.toLowerCase();
      if (
        !row.title.toLowerCase().includes(needle) &&
        !row.message.toLowerCase().includes(needle)
      ) {
        return false;
      }
    }
    return true;
  });
  const result = paginate(filtered, filters);
  return { ...result, items: result.items.map(toAlertRow) };
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
