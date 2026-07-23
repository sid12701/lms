/**
 * Audit explorer API client — cursor pagination against the unified backend endpoint.
 */
import { buildQueryPath, requestJson } from "@/lib/api/http-client";
import {
  type AuditEventsFilters,
  type AuditEventsResponse,
  type AuditRow,
  type AuditStream,
  type AuditSubjectType,
} from "./types";

const ENDPOINT = "/api/v1/internal/admin/audit-events";
const DEFAULT_PAGE_SIZE = 25;
const DEFAULT_WINDOW_DAYS = 7;

const BACKEND_STREAMS: ReadonlySet<AuditStream> = new Set<AuditStream>([
  "APPLICATION",
  "INTAKE",
  "DOCUMENT_ACCESS",
  "PRODUCT",
  "APP_USER",
  "API_CLIENT",
  "DISBURSEMENT",
  "REPORT_ACCESS",
]);

interface BackendUnifiedAuditEvent {
  id: string;
  stream: AuditStream;
  occurredAt: string;
  actorUsername: string;
  loanApplicationId: string | null;
  borrowerId: string | null;
  lspId: string | null;
  productId: string | null;
  action: string;
  summary: string;
  detail: Record<string, unknown>;
  correlationId: string | null;
}

interface BackendCursorPagedAuditEvents {
  items: BackendUnifiedAuditEvent[];
  nextCursor: string | null;
  limit: number;
}

function backendSubsetOf(streams: readonly AuditStream[] | undefined): AuditStream[] | undefined {
  if (!streams || streams.length === 0) return undefined;
  const filtered = streams.filter((s) => BACKEND_STREAMS.has(s));
  return filtered.length > 0 ? filtered : undefined;
}

function defaultDateFrom(): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - DEFAULT_WINDOW_DAYS);
  return date.toISOString().slice(0, 10);
}

function toIsoStart(date: string | undefined): string | undefined {
  return date ? `${date}T00:00:00Z` : `${defaultDateFrom()}T00:00:00Z`;
}

function toIsoEnd(date: string | undefined): string | undefined {
  return date ? `${date}T23:59:59.999Z` : undefined;
}

function buildBackendQueryParams(
  filters: AuditEventsFilters,
): Record<string, string | number | undefined> {
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const streams = backendSubsetOf(filters.streams);

  const params: Record<string, string | number | undefined> = {
    limit: pageSize,
  };
  if (filters.cursor) params["cursor"] = filters.cursor;
  if (streams) params["streams"] = streams.join(",");
  if (filters.actorId) params["actorUsername"] = filters.actorId;
  if (filters.loanApplicationId) params["loanApplicationId"] = filters.loanApplicationId;
  if (filters.correlationId) params["correlationId"] = filters.correlationId;
  params["since"] = toIsoStart(filters.dateFrom);
  const until = toIsoEnd(filters.dateTo);
  if (until) params["until"] = until;
  return params;
}

function readDetailId(detail: Record<string, unknown>, key: string): string | null {
  const value = detail[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function subjectFor(event: BackendUnifiedAuditEvent): {
  subjectType: AuditSubjectType | null;
  subjectId: string | null;
} {
  if (event.stream === "PRODUCT") {
    return { subjectType: "LOAN_PRODUCT", subjectId: event.productId };
  }
  if (event.stream === "APP_USER") {
    return {
      subjectType: "APP_USER",
      subjectId: readDetailId(event.detail, "userId"),
    };
  }
  if (event.stream === "API_CLIENT") {
    return {
      subjectType: "API_CLIENT",
      subjectId: readDetailId(event.detail, "apiClientId"),
    };
  }
  if (event.stream === "PII_REVEAL" && event.borrowerId) {
    return { subjectType: "BORROWER", subjectId: event.borrowerId };
  }
  if (event.loanApplicationId) {
    return { subjectType: "LOAN_APPLICATION", subjectId: event.loanApplicationId };
  }
  return { subjectType: null, subjectId: null };
}

function projectAuditRow(event: BackendUnifiedAuditEvent): AuditRow {
  const subject = subjectFor(event);
  return {
    id: event.id,
    stream: event.stream,
    createdAt: event.occurredAt,
    actorId: event.actorUsername,
    actorName: event.actorUsername,
    actorRole: null,
    correlationId: event.correlationId ?? event.id,
    subjectType: subject.subjectType,
    subjectId: subject.subjectId,
    headline: event.summary && event.summary.trim() !== "" ? event.summary : humanize(event.action),
    raw: event,
  };
}

function humanize(action: string | null | undefined): string {
  if (!action) return "Audit event";
  const lowered = action.replace(/[_-]/g, " ").toLowerCase();
  return lowered.charAt(0).toUpperCase() + lowered.slice(1);
}

async function fetchFromBackend(filters: AuditEventsFilters): Promise<AuditEventsResponse> {
  const path = buildQueryPath(ENDPOINT, buildBackendQueryParams(filters));
  const payload = await requestJson<BackendCursorPagedAuditEvents>(path);
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;

  return {
    items: payload.items.map(projectAuditRow),
    total: payload.items.length,
    page: filters.page ?? 0,
    pageSize,
    nextCursor: payload.nextCursor,
  };
}

export async function fetchAuditEvents(filters: AuditEventsFilters): Promise<AuditEventsResponse> {
  return fetchFromBackend(filters);
}
