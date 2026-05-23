/**
 * Alerts inbox, wired to the live backend.
 *
 * Backend contract: `OpsAlertController` under `/api/v1/internal/alerts`
 * (SYSTEM_ADMIN + OPS_USER). The acknowledge endpoint takes no body
 * payload — the frontend's `note` field is dropped on the way out
 * (documented in docs/INTEGRATION-STATUS.md).
 */
import { requestJson, buildQueryPath } from "@/lib/api/http-client";
import type {
  AlertRow,
  AlertsListFilters,
  AlertsListResponse,
  AcknowledgeAlertInput,
  AcknowledgeAlertResponse,
} from "./types";
import type {
  AlertSeverity,
  AlertStatus,
  AlertSubjectType,
} from "@/schemas/alert";

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
    acknowledgmentNote: null,
    acknowledgedByName: payload.acknowledgedByUsername ?? null,
  };
}

export async function listAlerts(
  filters: AlertsListFilters = {},
): Promise<AlertsListResponse> {
  const path = buildQueryPath(BASE, {
    status: backendStatus(filters.status),
  });
  const all = await requestJson<BackendAlertResponse[]>(path);
  const filtered = all.filter((row) => {
    if (filters.severity && filters.severity.length > 0) {
      if (!filters.severity.includes(frontendSeverity(row.severity))) return false;
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
  const page = filters.page ?? 0;
  const pageSize = filters.pageSize ?? 20;
  const items = filtered.slice(page * pageSize, page * pageSize + pageSize).map(toAlertRow);
  return { items, total: filtered.length, page, pageSize };
}

export async function acknowledgeAlert(
  id: string,
  input: AcknowledgeAlertInput,
): Promise<AcknowledgeAlertResponse> {
  const payload = await requestJson<BackendAlertResponse>(
    `${BASE}/${id}/acknowledge`,
    { method: "POST" },
    { idempotencyKey: input.idempotencyKey },
  );
  const row = toAlertRow(payload);
  if (input.note) row.acknowledgmentNote = input.note;
  return { alert: row };
}
