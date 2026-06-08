/**
 * Audit explorer API client.
 *
 * Gap #3 / #76 — the backend exposes a single unified endpoint at
 *   GET /api/v1/internal/admin/audit-events
 * that UNION-ALLs across the eight supported audit streams on the server
 * side. This module routes the page's URL-bound filters into that request
 * and projects each row into the long-standing {@link AuditRow} shape so
 * the table/sheet keep rendering unchanged. All filters are server-side.
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

/** Streams backed by the live unified audit endpoint (#159 / #151). */
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

interface BackendPagedAuditEvents {
  items: BackendUnifiedAuditEvent[];
  totalCount: number;
  offset: number;
  limit: number;
}

function backendSubsetOf(streams: readonly AuditStream[] | undefined): AuditStream[] | undefined {
  if (!streams || streams.length === 0) return undefined;
  const filtered = streams.filter((s) => BACKEND_STREAMS.has(s));
  return filtered.length > 0 ? filtered : undefined;
}

function toIsoStart(date: string | undefined): string | undefined {
  if (!date) return undefined;
  return `${date}T00:00:00Z`;
}

function toIsoEnd(date: string | undefined): string | undefined {
  if (!date) return undefined;
  return `${date}T23:59:59.999Z`;
}

function buildBackendQueryParams(
  filters: AuditEventsFilters,
): Record<string, string | number | undefined> {
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;
  const streams = backendSubsetOf(filters.streams);

  const params: Record<string, string | number | undefined> = {
    paginationDetails: "true",
    limit: pageSize,
    offset: page * pageSize,
  };
  if (streams) params["streams"] = streams.join(",");
  if (filters.actorId) params["actorUsername"] = filters.actorId;
  if (filters.loanApplicationId) params["loanApplicationId"] = filters.loanApplicationId;
  if (filters.correlationId) params["correlationId"] = filters.correlationId;
  const since = toIsoStart(filters.dateFrom);
  if (since) params["since"] = since;
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
  const payload = await requestJson<BackendPagedAuditEvents>(path);
  const pageSize = filters.pageSize ?? DEFAULT_PAGE_SIZE;
  const page = filters.page ?? 0;

  const items = payload.items.map(projectAuditRow);
  const total = payload.totalCount >= 0 ? payload.totalCount : items.length + page * pageSize;

  return {
    items,
    total,
    page,
    pageSize,
  };
}

/**
 * Fetch the audit-events page from the live unified backend endpoint.
 */
export async function fetchAuditEvents(filters: AuditEventsFilters): Promise<AuditEventsResponse> {
  return fetchFromBackend(filters);
}

/** Retained for URL-bound filter callers that still want to serialise a snapshot. */
export function buildAuditEventsQuery(filters: AuditEventsFilters): string {
  const params = buildBackendQueryParams(filters);
  const out = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") out.set(key, String(value));
  }
  return out.toString();
}

export type { AuditStream };
