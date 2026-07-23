/**
 * Phase 9 — `/lsps` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes list filters, table, create, status change (kill chain), audit trail,
 * and webhook subscription dialogs. All writes go to the live backend under
 * `/api/v1/internal/admin/lsps`.
 */
import { useMemo, useState } from "react";
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
import { LspWebhookSubscriptionDialog } from "./components/LspWebhookSubscriptionDialog";
import { useLsps } from "./hooks/useLsps";
import { useCreateLsp } from "./hooks/useCreateLsp";
import { useUpdateLspStatus } from "./hooks/useUpdateLspStatus";
import { useLspAuditEvents } from "./hooks/useLspAuditEvents";
import { useLspWebhookSubscription } from "./hooks/useLspWebhookSubscription";
import { useUpsertLspWebhookSubscription } from "./hooks/useUpsertLspWebhookSubscription";
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
  | { kind: "webhook"; lsp: LspRow }
  | { kind: "allowlist"; lsp: LspRow };

export function LspsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);

  const setFilters = (next: LspsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [dialog, setDialog] = useState<LspDialogState>({ kind: "none" });

  const createOpen = dialog.kind === "create";
  const detailsTarget = dialog.kind === "details" ? dialog.lsp : null;
  const statusTarget = dialog.kind === "status" ? dialog.lsp : null;
  const auditTarget = dialog.kind === "audit" ? dialog.lsp : null;
  const webhookTarget = dialog.kind === "webhook" ? dialog.lsp : null;
  const allowlistTarget = dialog.kind === "allowlist" ? dialog.lsp : null;

  const list = useLsps(filters);
  const listLoading = list.isPending || (list.isFetching && list.data === undefined);
  const create = useCreateLsp();
  const updateStatus = useUpdateLspStatus();
  const upsertWebhook = useUpsertLspWebhookSubscription();
  const webhookQuery = useLspWebhookSubscription(webhookTarget?.id ?? null);
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

  const handleWebhookOpenChange = (open: boolean) => {
    if (!open) {
      if (upsertWebhook.isPending) return;
      setDialog({ kind: "none" });
      upsertWebhook.reset();
    }
  };
  const handleWebhookConfirm = async ({
    id,
    enabled,
    endpointUrl,
    signingSecret,
    eventTypes,
    idempotencyKey,
  }: {
    id: string;
    enabled: boolean;
    endpointUrl: string;
    signingSecret: string;
    eventTypes: import("@/schemas/lsp").WebhookEventType[];
    idempotencyKey: string;
  }) => {
    try {
      await upsertWebhook.mutateAsync({
        id,
        enabled,
        endpointUrl,
        signingSecret,
        eventTypes,
        idempotencyKey,
      });
      setDialog({ kind: "none" });
      upsertWebhook.reset();
    } catch {
      // Surfaced via upsertWebhook.error.
    }
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
      description="Manage Lending Service Provider tenants, operational status, and webhook subscriptions."
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
          onDetails={(lsp) => setDialog({ kind: "details", lsp })}
          onChangeStatus={(lsp) => setDialog({ kind: "status", lsp })}
          onViewAudit={(lsp) => setDialog({ kind: "audit", lsp })}
          onEditWebhook={(lsp) => setDialog({ kind: "webhook", lsp })}
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

          <LspWebhookSubscriptionDialog
            open={webhookTarget !== null}
            onOpenChange={handleWebhookOpenChange}
            lspId={webhookTarget?.id ?? null}
            lspLabel={webhookTarget ? `${webhookTarget.code} — ${webhookTarget.name}` : ""}
            initialSubscription={webhookQuery.data?.subscription ?? null}
            onConfirm={handleWebhookConfirm}
            loading={upsertWebhook.isPending}
            errorMessage={
              upsertWebhook.isError ? extractAdminErrorMessage(upsertWebhook.error) : null
            }
          />
        </>
      }
    />
  );
}

export default LspsPage;
export const Component = LspsPage;
