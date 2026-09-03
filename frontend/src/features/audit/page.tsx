/**
 * Phase 8 SYSTEM_ADMIN-only audit explorer (locked decision D4).
 *
 * Composes the landed audit primitives into a single page:
 *
 *   PageHeader → AuditPageFilterBar → AuditTable → AuditEventDetailSheet
 *
 * Filter state is URL-bound: `streams`, `actorId`, `loanApplicationId`,
 * `correlationId`, `dateFrom`, `dateTo`, `page`, `pageSize`, `eventId`. Deep links
 * from the Alerts table (`/audit?correlationId=…`) pre-fill the bar. Coercion
 * lives in `./url-filters`; anything the URL asked for that could not be
 * applied is handed to the bar and named there rather than dropped in silence.
 *
 * Selecting a row writes `eventId` to the URL and opens the right-anchored
 * detail sheet; closing it strips `eventId`. The detail event is resolved
 * out of the currently-loaded page (no per-event fetch — the projection
 * carries everything the sheet needs).
 *
 * Role enforcement runs in two layers:
 *   1) page short-circuits when the session role !== SYSTEM_ADMIN, so the
 *      query never fires and OPS_USER does not even hit the handler;
 *   2) the handler itself raises UNAUTHORIZED for non-admin roles — the
 *      page surfaces that as a friendly EmptyState if a stale token would
 *      ever slip past the router guard.
 */
import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";
import { useSearchParams } from "react-router-dom";
import { Lock, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { isUnauthorizedApiError } from "@/lib/api/api-errors";
import { useSession } from "@/features/auth/session-context";
import { useAuditEvents } from "./hooks/useAuditEvents";
import { type AuditEventsFilters, type AuditRow } from "./types";
import { AuditPageFilterBar, type ActorOption } from "./components/AuditFilterBar";
import { AuditTable } from "./components/AuditTable";
import { AuditEventDetailSheet } from "./components/AuditEventDetailSheet";
import { parseAuditFiltersFromUrl, serializeAuditFiltersToUrl } from "./url-filters";
import {
  accumulateAuditRowsReducer,
  auditFilterKey,
  type AccumulatedAuditRowsState,
} from "./accumulateAuditRows";

function distinctActorOptions(rows: ReadonlyArray<AuditRow>): ActorOption[] {
  const seen = new Map<string, string>();
  for (const row of rows) {
    if (!seen.has(row.actorId)) {
      seen.set(row.actorId, row.actorName);
    }
  }
  return Array.from(seen, ([value, label]) => ({ value, label })).sort((a, b) =>
    a.label.localeCompare(b.label),
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export function AuditPage() {
  const { session } = useSession();
  const role = session?.user.role ?? null;
  const isSystemAdmin = role === "SYSTEM_ADMIN";

  const [searchParams, setSearchParams] = useSearchParams();
  const parsed = useMemo(() => parseAuditFiltersFromUrl(searchParams), [searchParams]);
  const filters = parsed.filters;

  const updateFilters = useCallback(
    (next: AuditEventsFilters) => {
      setSearchParams(serializeAuditFiltersToUrl(next));
    },
    [setSearchParams],
  );

  // Strip eventId before firing the query — it's a UI-only key for the sheet
  // and the handler does not understand it.
  const queryFilters = useMemo<AuditEventsFilters>(() => {
    const { eventId: _ignored, ...rest } = filters;
    return rest;
  }, [filters]);

  const query = useAuditEvents(queryFilters, { enabled: isSystemAdmin });

  const filterKey = useMemo(() => auditFilterKey(queryFilters), [queryFilters]);
  const [rowState, dispatchRows] = useReducer(accumulateAuditRowsReducer, {
    filterKey: "",
    rows: [],
  } satisfies AccumulatedAuditRowsState);
  const appliedFetchRef = useRef<string | null>(null);

  useEffect(() => {
    if (!query.data || !query.isSuccess) {
      if (query.isPending && !query.data) {
        appliedFetchRef.current = null;
      }
      return;
    }

    const fetchToken = `${filterKey}|${queryFilters.cursor ?? "initial"}|${query.dataUpdatedAt}`;
    if (appliedFetchRef.current === fetchToken) {
      return;
    }

    appliedFetchRef.current = fetchToken;
    const action = {
      type: queryFilters.cursor ? ("append" as const) : ("replace" as const),
      filterKey,
      items: query.data.items,
    };

    const frame = requestAnimationFrame(() => {
      dispatchRows(action);
    });

    return () => {
      cancelAnimationFrame(frame);
    };
  }, [
    filterKey,
    query.data,
    query.dataUpdatedAt,
    query.isPending,
    query.isSuccess,
    queryFilters.cursor,
  ]);

  const rows = rowState.rows;
  const actorOptions = useMemo(() => distinctActorOptions(rows), [rows]);

  const selectedEvent = useMemo<AuditRow | null>(() => {
    if (!filters.eventId) return null;
    return rows.find((r) => r.id === filters.eventId) ?? null;
  }, [filters.eventId, rows]);

  const handleSelect = useCallback(
    (row: AuditRow) => {
      updateFilters({ ...filters, eventId: row.id });
    },
    [filters, updateFilters],
  );

  const handleSheetOpenChange = useCallback(
    (open: boolean) => {
      if (open) return;
      const { eventId: _ignored, ...rest } = filters;
      updateFilters(rest);
    },
    [filters, updateFilters],
  );

  if (!isSystemAdmin) {
    return (
      <div className="flex flex-col gap-6 p-6" data-testid="audit-page">
        <PageHeader
          title="Audit log"
          description="Application, intake, document access, product, app user, API client, disbursement, and report access audit streams."
        />
        <EmptyState
          variant="no-permission"
          icon={Lock}
          title="Restricted to system administrators"
          description="The unified audit log is only available to SYSTEM_ADMIN users."
        />
      </div>
    );
  }

  const unauthorized = query.isError && isUnauthorizedApiError(query.error);
  const loadFailed = query.isError && !unauthorized;

  return (
    <div className="flex flex-col gap-6 p-6" data-testid="audit-page">
      <PageHeader
        title="Audit log"
        description="Application, intake, document access, product, app user, API client, disbursement, and report access audit streams."
      />

      {unauthorized ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="Restricted to system administrators"
          description="Your session is no longer authorised to read the audit log."
        />
      ) : loadFailed ? (
        <ErrorState
          title="Couldn't load audit events"
          description="The audit log couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : (
        <>
          <AuditPageFilterBar
            value={filters}
            onChange={updateFilters}
            actorOptions={actorOptions}
            ignoredFilterKeys={parsed.ignoredKeys}
          />
          <AuditTable
            data={query.data ? { ...query.data, items: rows } : undefined}
            isLoading={query.isPending}
            onSelect={handleSelect}
            onLoadMore={
              query.data?.nextCursor
                ? () => updateFilters({ ...filters, cursor: query.data?.nextCursor ?? undefined })
                : undefined
            }
          />
          <AuditEventDetailSheet
            event={selectedEvent}
            open={selectedEvent !== null}
            onOpenChange={handleSheetOpenChange}
          />
        </>
      )}
    </div>
  );
}

export default AuditPage;
export const Component = AuditPage;
