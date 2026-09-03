/**
 * URL ↔ filter coercion for `/audit`.
 *
 * Extracted from the page for the same reason `accumulateAuditRows` was: it is
 * pure logic over a URLSearchParams snapshot, and the interesting cases are
 * malformed deep links rather than anything a rendered page can reach.
 *
 * Every key the URL supplies but that cannot be applied is reported back in
 * `ignoredKeys`, which the filter bar renders through `IgnoredFilterNotice`.
 * A filtered view that quietly becomes an unfiltered one misreports the size of
 * the book being looked at (finding F-01), so "dropped" must never be silent.
 */
import { AUDIT_STREAMS, type AuditEventsFilters, type AuditStream } from "./types";

const VALID_STREAM_SET = new Set<AuditStream>(AUDIT_STREAMS);

export interface ParsedAuditFilters {
  filters: AuditEventsFilters;
  /** Filter keys the URL supplied that could not be applied, in schema order. */
  ignoredKeys: readonly string[];
}

/** Schema key → the label the operator sees on that control. */
const FILTER_LABELS: Record<string, string> = {
  streams: "Stream",
  actorId: "Actor",
  loanApplicationId: "Loan application id",
  correlationId: "Correlation id",
  dateFrom: "From date",
  dateTo: "To date",
};

export function auditFilterLabelFor(key: string): string {
  return FILTER_LABELS[key] ?? key;
}

function readNumber(value: string | null): number | undefined {
  if (value === null || value === "") return undefined;
  const n = Number(value);
  if (!Number.isFinite(n)) return undefined;
  return n;
}

/**
 * Coerce `streams` to the single stream the selector can hold.
 *
 * The control has only ever emitted one stream at a time, but the param is
 * repeatable, so a hand-written link can name several. Applying just the first
 * would leave the select reading "Application" over a result set the link asked
 * to be wider — a partially-applied filter is the same lie as a dropped one.
 * So the key is all-or-nothing (matching `useUrlFilters`' per-key semantics)
 * and reports itself as ignored when it cannot be honoured exactly.
 */
function readStreams(params: URLSearchParams): {
  streams: AuditStream[] | undefined;
  ignored: boolean;
} {
  const supplied: string[] = [];
  for (const value of params.getAll("streams")) {
    for (const candidate of value.split(",")) {
      const trimmed = candidate.trim();
      if (trimmed !== "") supplied.push(trimmed);
    }
  }

  if (supplied.length === 0) return { streams: undefined, ignored: false };
  if (supplied.length > 1) return { streams: undefined, ignored: true };

  const only = supplied[0] as AuditStream;
  if (!VALID_STREAM_SET.has(only)) return { streams: undefined, ignored: true };
  return { streams: [only], ignored: false };
}

export function parseAuditFiltersFromUrl(params: URLSearchParams): ParsedAuditFilters {
  const out: AuditEventsFilters = {};
  const ignoredKeys: string[] = [];

  const streams = readStreams(params);
  if (streams.streams) out.streams = streams.streams;
  if (streams.ignored) ignoredKeys.push("streams");

  const actorId = params.get("actorId");
  if (actorId) out.actorId = actorId;
  const loanApplicationId = params.get("loanApplicationId");
  if (loanApplicationId) out.loanApplicationId = loanApplicationId;
  const correlationId = params.get("correlationId");
  if (correlationId) out.correlationId = correlationId;
  const dateFrom = params.get("dateFrom");
  if (dateFrom) out.dateFrom = dateFrom;
  const dateTo = params.get("dateTo");
  if (dateTo) out.dateTo = dateTo;
  const page = readNumber(params.get("page"));
  if (page !== undefined) out.page = page;
  const pageSize = readNumber(params.get("pageSize"));
  if (pageSize !== undefined) out.pageSize = pageSize;
  const eventId = params.get("eventId");
  if (eventId) out.eventId = eventId;

  return { filters: out, ignoredKeys };
}

export function serializeAuditFiltersToUrl(filters: AuditEventsFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.streams && filters.streams.length > 0) {
    // Repeated params — the backend accepts both this and comma-joined,
    // and repeated params are the convention used elsewhere in this codebase
    // (see useUrlFilters).
    for (const s of filters.streams) params.append("streams", s);
  }
  if (filters.actorId) params.set("actorId", filters.actorId);
  if (filters.loanApplicationId) params.set("loanApplicationId", filters.loanApplicationId);
  if (filters.correlationId) params.set("correlationId", filters.correlationId);
  if (filters.dateFrom) params.set("dateFrom", filters.dateFrom);
  if (filters.dateTo) params.set("dateTo", filters.dateTo);
  if (typeof filters.page === "number") params.set("page", String(filters.page));
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  if (filters.eventId) params.set("eventId", filters.eventId);
  return params;
}
