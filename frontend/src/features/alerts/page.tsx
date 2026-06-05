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
 * `SYSTEM_ADMIN_OR_OPS`). If a user without access reaches this page we
 * surface a friendly empty state instead of an `ErrorState` so the screen
 * never looks broken for a user who simply lacks access.
 */
import { useState } from "react";
import { Bell } from "lucide-react";
import { AdminEntityListPage } from "@/components/app/layout/AdminEntityListPage";
import { extractAdminErrorMessage } from "@/lib/admin-page-utils";
import { useSession } from "@/features/auth/session-context";
import { AlertRulesPanel } from "./components/AlertRulesPanel";
import { AlertsFilterBar } from "./components/AlertsFilterBar";
import { AlertsTable } from "./components/AlertsTable";
import { AcknowledgeAlertDialog } from "./components/AcknowledgeAlertDialog";
import { useAlertRules } from "./hooks/useAlertRules";
import { useAlerts } from "./hooks/useAlerts";
import { useAcknowledgeAlert } from "./hooks/useAcknowledgeAlert";
import type { AlertRow, AlertsListFilters } from "./types";

const INITIAL_FILTERS: AlertsListFilters = { page: 0, pageSize: 25 };

export function AlertsPage() {
  const [filters, setFilters] = useState<AlertsListFilters>(INITIAL_FILTERS);
  const [selectedAlertForAck, setSelectedAlertForAck] = useState<AlertRow | null>(null);

  const { session } = useSession();
  const isSystemAdmin = session?.user.role === "SYSTEM_ADMIN";

  const query = useAlerts(filters);
  const rulesQuery = useAlertRules(isSystemAdmin);
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

  const isCatalogueEmpty =
    !query.isPending &&
    (query.data?.total ?? 0) === 0 &&
    !filters.q &&
    !filters.status &&
    !filters.subjectType &&
    (filters.severity?.length ?? 0) === 0;

  return (
    <AdminEntityListPage
      testId="alerts-page"
      eyebrow="Reporting"
      title="Alerts"
      description="Operational alerts across applications, disbursements, and repayments."
      list={query}
      listPrefix={
        isSystemAdmin ? (
          <AlertRulesPanel rules={rulesQuery.data} isLoading={rulesQuery.isPending} />
        ) : null
      }
      unauthorized={{
        title: "No access to alerts",
        description: "The operational alerts inbox is restricted to system admins and ops users.",
      }}
      fetchError={{
        title: "Couldn't load alerts",
        description: "The alerts inbox couldn't be fetched. Try again in a moment.",
      }}
      isCatalogueEmpty={isCatalogueEmpty}
      catalogueEmpty={{
        icon: Bell,
        title: "No alerts yet",
        description: "When the platform fires an operational alert, it lands here.",
      }}
      filterBar={<AlertsFilterBar filters={filters} onChange={setFilters} />}
      table={
        <AlertsTable
          data={query.data}
          isLoading={query.isPending}
          filters={filters}
          onFiltersChange={setFilters}
          onAcknowledge={(row) => setSelectedAlertForAck(row)}
        />
      }
      dialogs={
        <AcknowledgeAlertDialog
          open={dialogOpen}
          onOpenChange={handleDialogOpenChange}
          alertTitle={selectedAlertForAck?.title ?? ""}
          onConfirm={handleConfirmAck}
          loading={acknowledge.isPending}
          errorMessage={acknowledge.isError ? extractAdminErrorMessage(acknowledge.error) : null}
        />
      }
    />
  );
}

export default AlertsPage;
export const Component = AlertsPage;
