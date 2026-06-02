/**
 * Phase 9 — `/lsps` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes list filters, table, create, status change (kill chain), audit trail,
 * and webhook subscription dialogs. All writes go to the live backend under
 * `/api/v1/internal/admin/lsps`.
 */
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Building2, Plus, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Button } from "@/components/ui/button";
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

function isUnauthorized(err: unknown): boolean {
  if (!err) return false;
  if (typeof err === "object" && err !== null && "code" in err) {
    const code = (err as { code?: unknown }).code;
    if (code === "UNAUTHORIZED") return true;
  }
  const msg = err instanceof Error ? err.message : String(err);
  return /UNAUTHORIZED/i.test(msg);
}

function extractErrorMessage(err: unknown): string | null {
  if (!err) return null;
  if (err instanceof Error && err.message) return err.message;
  return "Something went wrong. Try again in a moment.";
}

const VALID_STATUSES: readonly LspStatus[] = ["ACTIVE", "SUSPENDED", "INACTIVE"];

function parseFiltersFromUrl(params: URLSearchParams): LspsListFilters {
  const filters: LspsListFilters = {};
  const status = params.get("status");
  if (status && (VALID_STATUSES as readonly string[]).includes(status)) {
    filters.status = status as LspStatus;
  }
  const q = params.get("q");
  if (q && q.trim() !== "") filters.q = q.trim();
  const page = params.get("page");
  if (page !== null) {
    const n = Number(page);
    if (Number.isInteger(n) && n >= 0) filters.page = n;
  }
  const pageSize = params.get("pageSize");
  if (pageSize !== null) {
    const n = Number(pageSize);
    if (Number.isInteger(n) && n >= 5 && n <= 100) filters.pageSize = n;
  }
  return filters;
}

function filtersToParams(filters: LspsListFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.status) params.set("status", filters.status);
  if (filters.q) params.set("q", filters.q);
  if (typeof filters.page === "number" && filters.page > 0) {
    params.set("page", String(filters.page));
  }
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  return params;
}

export function LspsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);

  const setFilters = (next: LspsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [createOpen, setCreateOpen] = useState(false);
  const [detailsTarget, setDetailsTarget] = useState<LspRow | null>(null);
  const [statusTarget, setStatusTarget] = useState<LspRow | null>(null);
  const [auditTarget, setAuditTarget] = useState<LspRow | null>(null);
  const [webhookTarget, setWebhookTarget] = useState<LspRow | null>(null);
  const [allowlistTarget, setAllowlistTarget] = useState<LspRow | null>(null);

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
      setCreateOpen(false);
      create.reset();
    } else {
      setCreateOpen(true);
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
      setCreateOpen(false);
      create.reset();
    } catch {
      // Surfaced via create.error.
    }
  };

  const handleDetailsOpenChange = (open: boolean) => {
    if (!open) setDetailsTarget(null);
  };

  const handleStatusOpenChange = (open: boolean) => {
    if (!open) {
      if (updateStatus.isPending) return;
      setStatusTarget(null);
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
      setStatusTarget(null);
      setDetailsTarget(null);
      updateStatus.reset();
      if (lspRow) setAuditTarget(lspRow);
    } catch {
      // Surfaced via updateStatus.error.
    }
  };

  const handleAuditOpenChange = (open: boolean) => {
    if (!open) setAuditTarget(null);
  };

  const handleWebhookOpenChange = (open: boolean) => {
    if (!open) {
      if (upsertWebhook.isPending) return;
      setWebhookTarget(null);
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
      setWebhookTarget(null);
      upsertWebhook.reset();
    } catch {
      // Surfaced via upsertWebhook.error.
    }
  };

  const openStatusFromDetails = () => {
    if (!detailsTarget) return;
    setStatusTarget(detailsTarget);
    setDetailsTarget(null);
  };

  const openAuditFromDetails = () => {
    if (!detailsTarget) return;
    setAuditTarget(detailsTarget);
    setDetailsTarget(null);
  };

  return (
    <div data-testid="lsps-page" className="flex flex-col gap-6 p-6" data-density="comfortable">
      <PageHeader
        eyebrow="Administration"
        title="LSPs"
        description="Manage Loan Service Provider tenants, operational status, and webhook subscriptions."
        actions={
          <Button type="button" onClick={() => setCreateOpen(true)} data-slot="lsps-new-button">
            <Plus aria-hidden="true" className="size-4" />
            New LSP
          </Button>
        }
      />

      {list.isError && isUnauthorized(list.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to LSP admin"
          description="The LSP admin surface is restricted to system administrators."
        />
      ) : list.isError ? (
        <ErrorState
          title="Couldn't load LSPs"
          description="The LSP list couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void list.refetch();
            },
          }}
        />
      ) : (
        <>
          <LspsFilterBar filters={filters} onChange={setFilters} />
          {!listLoading && (list.data?.total ?? 0) === 0 && !filters.q && !filters.status ? (
            <EmptyState
              icon={Building2}
              title="No LSPs yet"
              description="Register the first Loan Service Provider tenant to get started."
            />
          ) : (
            <LspsTable
              data={list.data}
              isLoading={listLoading}
              filters={filters}
              onFiltersChange={setFilters}
              onDetails={(row) => setDetailsTarget(row)}
              onChangeStatus={(row) => setStatusTarget(row)}
              onViewAudit={(row) => setAuditTarget(row)}
              onEditWebhook={(row) => setWebhookTarget(row)}
            />
          )}
        </>
      )}

      <LspCreateDialog
        open={createOpen}
        onOpenChange={handleCreateOpenChange}
        onConfirm={handleCreateConfirm}
        loading={create.isPending}
        errorMessage={create.isError ? extractErrorMessage(create.error) : null}
      />

      <LspDetailsDialog
        open={detailsTarget !== null}
        onOpenChange={handleDetailsOpenChange}
        lsp={detailsTarget}
        onChangeStatus={openStatusFromDetails}
        onViewAudit={openAuditFromDetails}
        onManageIpAllowlists={() => {
          if (detailsTarget) {
            setAllowlistTarget(detailsTarget);
            setDetailsTarget(null);
          }
        }}
      />

      <LspIpAllowlistDialog
        open={allowlistTarget !== null}
        onOpenChange={(open) => {
          if (!open) setAllowlistTarget(null);
        }}
        lsp={allowlistTarget}
      />

      <LspStatusChangeDialog
        open={statusTarget !== null}
        onOpenChange={handleStatusOpenChange}
        lsp={statusTarget}
        onConfirm={handleStatusConfirm}
        loading={updateStatus.isPending}
        errorMessage={updateStatus.isError ? extractErrorMessage(updateStatus.error) : null}
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
        errorMessage={upsertWebhook.isError ? extractErrorMessage(upsertWebhook.error) : null}
      />
    </div>
  );
}

export default LspsPage;
export const Component = LspsPage;
