/**
 * Phase 8 — `/alerts` operational-alerts inbox.
 *
 * Composes the (already-shipped) filter bar, server-paged table, and the
 * acknowledge dialog. Filter state is page-local React state (not URL-bound
 * by design — see the inline comment in `AlertsFilterBar`: the inbox is an
 * internal-ops surface, not a deep-link target). The query cache key is the
 * filter snapshot so back/forward navigation hits the cache.
 *
 * Role enforcement lives in the router (`RequireRole` for
 * `SYSTEM_ADMIN_OR_OPS`). The mock handler also 401s defensively for any
 * other role; if that ever escapes the router gate we surface a friendly
 * empty state instead of an `ErrorState` so the screen never looks broken
 * for a user who simply lacks access.
 */
import { useState } from "react";
import { Bell, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { AlertsFilterBar } from "./components/AlertsFilterBar";
import { AlertsTable } from "./components/AlertsTable";
import { AcknowledgeAlertDialog } from "./components/AcknowledgeAlertDialog";
import { useAlerts } from "./hooks/useAlerts";
import { useAcknowledgeAlert } from "./hooks/useAcknowledgeAlert";
import type { AlertRow, AlertsListFilters } from "./types";

const INITIAL_FILTERS: AlertsListFilters = { page: 0, pageSize: 25 };

function isUnauthorized(err: unknown): boolean {
  if (!err) return false;
  // MockError shape — preferred check.
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (code === "UNAUTHORIZED") return true;
  }
  // Fallback per spec: message-based sniff in case the error was re-wrapped.
  const msg = err instanceof Error ? err.message : String(err);
  return /UNAUTHORIZED/i.test(msg);
}

function extractErrorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof Error && err.message) return err.message;
  return "Couldn't acknowledge the alert. Try again in a moment.";
}

export function AlertsPage() {
  const [filters, setFilters] = useState<AlertsListFilters>(INITIAL_FILTERS);
  const [selectedAlertForAck, setSelectedAlertForAck] = useState<AlertRow | null>(
    null,
  );

  const query = useAlerts(filters);
  const acknowledge = useAcknowledgeAlert();

  const dialogOpen = selectedAlertForAck !== null;

  const handleDialogOpenChange = (open: boolean) => {
    if (!open) {
      // Don't allow closing while the mutation is in-flight.
      if (acknowledge.isPending) return;
      setSelectedAlertForAck(null);
      acknowledge.reset();
    }
  };

  const handleConfirmAck = async ({
    note,
    idempotencyKey,
  }: {
    note: string | null;
    idempotencyKey: string;
  }) => {
    if (!selectedAlertForAck) return;
    try {
      await acknowledge.mutateAsync({
        id: selectedAlertForAck.id,
        note,
        idempotencyKey,
      });
      // Hook already invalidated the list + fired a sonner toast.
      setSelectedAlertForAck(null);
      acknowledge.reset();
    } catch {
      // Error surfaces via `acknowledge.error` → dialog `errorMessage` prop.
      // Keep dialog open so the user can retry / cancel.
    }
  };

  return (
    <div
      data-testid="alerts-page"
      className="flex flex-col gap-6 p-6"
      data-density="comfortable"
    >
      <PageHeader
        eyebrow="Reporting"
        title="Alerts"
        description="Operational alerts across applications, disbursements, and repayments."
      />

      {query.isError && isUnauthorized(query.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to alerts"
          description="The operational alerts inbox is restricted to system admins and ops users."
        />
      ) : query.isError ? (
        <ErrorState
          title="Couldn't load alerts"
          description="The alerts inbox couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : (
        <>
          <AlertsFilterBar filters={filters} onChange={setFilters} />
          {!query.isPending &&
          (query.data?.total ?? 0) === 0 &&
          !filters.q &&
          !filters.status &&
          !filters.subjectType &&
          (filters.severity?.length ?? 0) === 0 ? (
            <EmptyState
              icon={Bell}
              title="No alerts yet"
              description="When the platform fires an operational alert, it lands here."
            />
          ) : (
            <AlertsTable
              data={query.data}
              isLoading={query.isPending}
              filters={filters}
              onFiltersChange={setFilters}
              onAcknowledge={(row) => setSelectedAlertForAck(row)}
            />
          )}
        </>
      )}

      <AcknowledgeAlertDialog
        open={dialogOpen}
        onOpenChange={handleDialogOpenChange}
        alertTitle={selectedAlertForAck?.title ?? ""}
        onConfirm={handleConfirmAck}
        loading={acknowledge.isPending}
        errorMessage={
          acknowledge.isError ? extractErrorMessage(acknowledge.error) : null
        }
      />
    </div>
  );
}

export default AlertsPage;
export const Component = AlertsPage;
