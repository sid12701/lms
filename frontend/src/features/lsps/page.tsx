/**
 * Phase 9 — `/lsps` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes list filters, table, create, status change (kill chain), audit trail,
 * and IP allowlist dialogs. All writes go to the live backend under
 * `/api/v1/internal/admin/lsps`.
 */
import { useCallback, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Building2 } from "lucide-react";
import { AdminEntityListPage } from "@/components/app/layout/AdminEntityListPage";
import { extractAdminErrorMessage } from "@/lib/admin-page-utils";
import { LspsFilterBar } from "./components/LspsFilterBar";
import { LspsTable } from "./components/LspsTable";
import { LspCreateDialog } from "./components/LspCreateDialog";
import { LspDetailsDialog } from "./components/LspDetailsDialog";
import { LspIpAllowlistDialog } from "./components/LspIpAllowlistDialog";
import { LspStatusChangeDialog } from "./components/LspStatusChangeDialog";
import { LspAuditEventsDialog } from "./components/LspAuditEventsDialog";
import { useLsps } from "./hooks/useLsps";
import { useCreateLsp } from "./hooks/useCreateLsp";
import { useUpdateLspStatus } from "./hooks/useUpdateLspStatus";
import { useLspAuditEvents } from "./hooks/useLspAuditEvents";
import type { LspRow, LspsListFilters } from "./types";
import type { LspStatus } from "@/schemas/lsp";
import {
  readAdminListParams,
  readAllowedParam,
  writeAdminListParams,
} from "@/lib/admin-list-url-state";

const VALID_STATUSES: readonly LspStatus[] = ["ACTIVE", "SUSPENDED", "INACTIVE"];

function parseFiltersFromUrl(params: URLSearchParams): LspsListFilters {
  return {
    ...readAdminListParams(params),
    status: readAllowedParam(params, "status", VALID_STATUSES),
  };
}

function filtersToParams(filters: LspsListFilters): URLSearchParams {
  const params = writeAdminListParams(filters);
  if (filters.status) params.set("status", filters.status);
  return params;
}

type LspDialogState =
  | { kind: "none" }
  | { kind: "create" }
  | { kind: "details"; lsp: LspRow }
  | { kind: "status"; lsp: LspRow }
  | { kind: "audit"; lsp: LspRow }
  | { kind: "allowlist"; lsp: LspRow };

export function LspsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);

  const setFilters = (next: LspsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [dialog, setDialog] = useState<LspDialogState>({ kind: "none" });

  /*
    Memoised because `LspsTable` lists these in its `columns` dependency array,
    and TanStack renders each column's `cell` function as a React component type
    — so a new identity remounts every cell, discarding the row button the
    operator just activated and leaving the dialog nothing to return focus to.
  */
  const openDetails = useCallback((lsp: LspRow) => setDialog({ kind: "details", lsp }), []);
  const openChangeStatus = useCallback((lsp: LspRow) => setDialog({ kind: "status", lsp }), []);
  const openViewAudit = useCallback((lsp: LspRow) => setDialog({ kind: "audit", lsp }), []);

  const createOpen = dialog.kind === "create";
  const detailsTarget = dialog.kind === "details" ? dialog.lsp : null;
  const statusTarget = dialog.kind === "status" ? dialog.lsp : null;
  const auditTarget = dialog.kind === "audit" ? dialog.lsp : null;
  const allowlistTarget = dialog.kind === "allowlist" ? dialog.lsp : null;

  const list = useLsps(filters);
  const listLoading = list.isPending || (list.isFetching && list.data === undefined);
  const create = useCreateLsp();
  const updateStatus = useUpdateLspStatus();
  const auditQuery = useLspAuditEvents(auditTarget?.id ?? null, auditTarget !== null);

  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setDialog({ kind: "none" });
      create.reset();
    } else {
      setDialog({ kind: "create" });
    }
  };
  const handleCreateConfirm = async ({
    code,
    name,
    idempotencyKey,
  }: {
    code: string;
    name: string;
    idempotencyKey: string;
  }) => {
    try {
      await create.mutateAsync({ code, name, idempotencyKey });
      setDialog({ kind: "none" });
      create.reset();
    } catch {
      // Surfaced via create.error.
    }
  };

  const handleDetailsOpenChange = (open: boolean) => {
    if (!open) setDialog({ kind: "none" });
  };

  const handleStatusOpenChange = (open: boolean) => {
    if (!open) {
      if (updateStatus.isPending) return;
      setDialog({ kind: "none" });
      updateStatus.reset();
    }
  };
  const handleStatusConfirm = async (args: {
    id: string;
    status: import("@/schemas/lsp").LspOperationalStatus;
    reason: import("@/schemas/lsp").LspStatusChangeReason;
    note: string;
    idempotencyKey: string;
  }) => {
    const lspRow = statusTarget;
    try {
      await updateStatus.mutateAsync(args);
      updateStatus.reset();
      setDialog(lspRow ? { kind: "audit", lsp: lspRow } : { kind: "none" });
    } catch {
      // Surfaced via updateStatus.error.
    }
  };

  const handleAuditOpenChange = (open: boolean) => {
    if (!open) setDialog({ kind: "none" });
  };

  const openStatusFromDetails = () => {
    if (!detailsTarget) return;
    setDialog({ kind: "status", lsp: detailsTarget });
  };

  const openAuditFromDetails = () => {
    if (!detailsTarget) return;
    setDialog({ kind: "audit", lsp: detailsTarget });
  };

  const isCatalogueEmpty =
    !listLoading && (list.data?.total ?? 0) === 0 && !filters.q && !filters.status;

  return (
    <AdminEntityListPage
      testId="lsps-page"
      title="LSPs"
      description="Manage Lending Service Provider tenants, operational status, and IP allowlists."
      primaryAction={{
        label: "New LSP",
        dataSlot: "lsps-new-button",
        onClick: () => setDialog({ kind: "create" }),
      }}
      list={list}
      unauthorized={{
        title: "No access to LSP admin",
        description: "The LSP admin surface is restricted to system administrators.",
      }}
      fetchError={{
        title: "Couldn't load LSPs",
        description: "The LSP list couldn't be fetched. Try again in a moment.",
      }}
      isCatalogueEmpty={isCatalogueEmpty}
      catalogueEmpty={{
        icon: Building2,
        title: "No LSPs yet",
        description: "Register the first Lending Service Provider tenant to get started.",
      }}
      filterBar={<LspsFilterBar filters={filters} onChange={setFilters} />}
      table={
        <LspsTable
          data={list.data}
          isLoading={listLoading}
          filters={filters}
          onFiltersChange={setFilters}
          onDetails={openDetails}
          onChangeStatus={openChangeStatus}
          onViewAudit={openViewAudit}
        />
      }
      dialogs={
        <>
          <LspCreateDialog
            open={createOpen}
            onOpenChange={handleCreateOpenChange}
            onConfirm={handleCreateConfirm}
            loading={create.isPending}
            errorMessage={create.isError ? extractAdminErrorMessage(create.error) : null}
          />

          <LspDetailsDialog
            open={detailsTarget !== null}
            onOpenChange={handleDetailsOpenChange}
            lsp={detailsTarget}
            onChangeStatus={openStatusFromDetails}
            onViewAudit={openAuditFromDetails}
            onManageIpAllowlists={() => {
              if (detailsTarget) {
                setDialog({ kind: "allowlist", lsp: detailsTarget });
              }
            }}
          />

          <LspIpAllowlistDialog
            open={allowlistTarget !== null}
            onOpenChange={(open) => {
              if (!open) setDialog({ kind: "none" });
            }}
            lsp={allowlistTarget}
          />

          <LspStatusChangeDialog
            open={statusTarget !== null}
            onOpenChange={handleStatusOpenChange}
            lsp={statusTarget}
            onConfirm={handleStatusConfirm}
            loading={updateStatus.isPending}
            errorMessage={
              updateStatus.isError ? extractAdminErrorMessage(updateStatus.error) : null
            }
          />

          <LspAuditEventsDialog
            open={auditTarget !== null}
            onOpenChange={handleAuditOpenChange}
            lsp={auditTarget}
            events={auditQuery.data}
            isLoading={auditQuery.isPending}
            isError={auditQuery.isError}
            onRetry={() => {
              void auditQuery.refetch();
            }}
          />
        </>
      }
    />
  );
}

export default LspsPage;
export const Component = LspsPage;
