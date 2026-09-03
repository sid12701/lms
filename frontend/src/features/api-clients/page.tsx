/**
 * Phase 9 — `/api-clients` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes:
 *   - `ApiClientsFilterBar` (URL-bound filters via `useSearchParams`)
 *   - `ApiClientsTable` (server-paged TanStack table with Edit + Rotate actions)
 *   - `ApiClientCreateDialog` (POST /api/v1/internal/admin/api-clients)
 *   - `ApiClientEditDialog` (PUT /api/v1/internal/admin/api-clients/{id}) —
 *     rename + enable/disable
 *   - Shared `RotateSecretDialog` from `@/components/app/secrets/...`
 *   - Shared `ApiSecretReveal` banner (renders the cleartext secret exactly
 *     once — surfaced both on create and on rotate)
 *
 * Role enforcement is server-side (backend 401s non-admin) AND
 * client-side (router-level RequireRole). When a non-admin somehow lands
 * here we surface a friendly EmptyState instead of an ErrorState — same
 * pattern as the LSPs page.
 *
 * Density default = comfortable per D7 (admin list is short).
 *
 * IP allow-lists are managed per LSP (Administration → LSPs), not per client.
 */
import { useCallback, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { KeyRound } from "lucide-react";
import { AdminEntityListPage } from "@/components/app/layout/AdminEntityListPage";
import { extractAdminErrorMessage } from "@/lib/admin-page-utils";
import { ApiSecretReveal, RotateSecretDialog } from "@/components/app/secrets";
import { ApiClientsFilterBar } from "./components/ApiClientsFilterBar";
import { ApiClientsTable } from "./components/ApiClientsTable";
import { ApiClientCreateDialog } from "./components/ApiClientCreateDialog";
import { ApiClientEditDialog } from "./components/ApiClientEditDialog";
import { useApiClients } from "./hooks/useApiClients";
import { useCreateApiClient } from "./hooks/useCreateApiClient";
import { useUpdateApiClient } from "./hooks/useUpdateApiClient";
import { useRotateApiClientSecret } from "./hooks/useRotateApiClientSecret";
import { useLsps } from "@/features/lsps/hooks/useLsps";
import type { ApiClientRow, ApiClientsListFilters } from "./types";
import type { ApiClientStatus } from "@/schemas/user";
import {
  readAdminListParams,
  readAllowedParam,
  readUuidParam,
  writeAdminListParams,
} from "@/lib/admin-list-url-state";

const VALID_STATUSES: readonly ApiClientStatus[] = ["ACTIVE", "DISABLED"];

function parseFiltersFromUrl(params: URLSearchParams): ApiClientsListFilters {
  return {
    ...readAdminListParams(params),
    status: readAllowedParam(params, "status", VALID_STATUSES),
    lspId: readUuidParam(params, "lspId"),
  };
}

function filtersToParams(filters: ApiClientsListFilters): URLSearchParams {
  const params = writeAdminListParams(filters);
  if (filters.status) params.set("status", filters.status);
  if (filters.lspId) params.set("lspId", filters.lspId);
  return params;
}

type ApiClientDialogState =
  | { kind: "none" }
  | { kind: "create" }
  | { kind: "edit"; client: ApiClientRow }
  | { kind: "rotate-secret"; client: ApiClientRow };

export function ApiClientsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseFiltersFromUrl(searchParams), [searchParams]);

  const setFilters = (next: ApiClientsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [dialog, setDialog] = useState<ApiClientDialogState>({ kind: "none" });
  const [revealedSecret, setRevealedSecret] = useState<{
    clientName: string;
    secret: string;
  } | null>(null);

  const list = useApiClients(filters);
  const listLoading = list.isPending || (list.isFetching && list.data === undefined);
  const create = useCreateApiClient();
  const update = useUpdateApiClient();
  const rotate = useRotateApiClientSecret();

  // Surface every LSP for the filter + create dropdowns. We ask for a large
  // page size because the admin tenant count is small (well under 100); if
  // it ever grew, this would move to a dedicated lookup hook.
  const lspsQuery = useLsps({ pageSize: 100 });
  const lspOptions = useMemo(
    () =>
      (lspsQuery.data?.items ?? []).map((row) => ({
        id: row.id,
        name: row.name,
      })),
    [lspsQuery.data],
  );
  const createOpen = dialog.kind === "create";
  const editTarget = dialog.kind === "edit" ? dialog.client : null;
  const rotateTarget = dialog.kind === "rotate-secret" ? dialog.client : null;

  /*
    Memoised because `ApiClientsTable` lists these in its `columns` dependency
    array, and TanStack renders each column's `cell` function as a React
    component type — so a new identity remounts every cell, discarding the row
    button the operator just activated and leaving the dialog nothing to return
    focus to on close.
  */
  const openEdit = useCallback((client: ApiClientRow) => setDialog({ kind: "edit", client }), []);
  const openRotate = useCallback(
    (client: ApiClientRow) => setDialog({ kind: "rotate-secret", client }),
    [],
  );

  // ── Create dialog handlers ─────────────────────────────────────────────────
  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setDialog({ kind: "none" });
      create.reset();
    } else {
      setDialog({ kind: "create" });
    }
  };
  const handleCreate = async (input: { name: string; lspId: string; idempotencyKey: string }) => {
    // `ApiClientCreateDialog` renders its own `ApiSecretReveal` inside the
    // dialog body on success — but we also surface the secret as a banner on
    // the page so it persists if the operator closes the dialog before
    // copying. The dialog only echoes back the response; the page is the
    // canonical owner of the "reveal-once" lifecycle.
    const res = await create.mutateAsync(input);
    setRevealedSecret({
      clientName: res.client.name,
      secret: res.clientSecret,
    });
    return res;
  };

  // ── Edit dialog handlers ───────────────────────────────────────────────────
  const handleEditOpenChange = (open: boolean) => {
    if (!open) {
      if (update.isPending) return;
      setDialog({ kind: "none" });
      update.reset();
    }
  };
  const handleEditSave = async ({
    name,
    status,
    idempotencyKey,
  }: {
    name: string;
    status: ApiClientStatus;
    idempotencyKey: string;
  }) => {
    if (!editTarget) return;
    try {
      await update.mutateAsync({ id: editTarget.id, name, status, idempotencyKey });
      setDialog({ kind: "none" });
      update.reset();
    } catch {
      // Surfaced via `update.error` — kept inside the dialog while the operator
      // decides whether to retry or cancel.
    }
  };

  // ── Rotate dialog handlers ─────────────────────────────────────────────────
  const handleRotateOpenChange = (open: boolean) => {
    if (!open) {
      if (rotate.isPending) return;
      setDialog({ kind: "none" });
      rotate.reset();
    }
  };
  const handleRotateConfirm = async ({
    reason,
    idempotencyKey,
  }: {
    reason: string;
    idempotencyKey: string;
  }) => {
    if (!rotateTarget) return;
    const clientName = rotateTarget.name;
    try {
      const res = await rotate.mutateAsync({
        id: rotateTarget.id,
        reason,
        idempotencyKey,
      });
      setRevealedSecret({
        clientName,
        secret: res.clientSecret,
      });
      setDialog({ kind: "none" });
      rotate.reset();
    } catch {
      // Surfaced via `rotate.error` — kept inside the dialog while the
      // operator decides whether to retry or cancel.
    }
  };

  const isCatalogueEmpty =
    !listLoading &&
    (list.data?.total ?? 0) === 0 &&
    !filters.q &&
    !filters.status &&
    !filters.lspId;

  return (
    <AdminEntityListPage
      testId="api-clients-page"
      title="API clients"
      description="LSP API credentials and secret rotation. IP allowlists are managed per LSP under Administration → LSPs."
      primaryAction={{
        label: "New API client",
        dataSlot: "api-clients-new-button",
        onClick: () => setDialog({ kind: "create" }),
      }}
      banner={
        revealedSecret && !createOpen ? (
          <ApiSecretReveal
            secret={revealedSecret.secret}
            clientLabel={revealedSecret.clientName}
            onAcknowledge={() => setRevealedSecret(null)}
          />
        ) : null
      }
      list={list}
      unauthorized={{
        title: "No access to API clients",
        description: "The API client admin surface is restricted to system administrators.",
      }}
      fetchError={{
        title: "Couldn't load API clients",
        description: "The API client list couldn't be fetched. Try again in a moment.",
      }}
      isCatalogueEmpty={isCatalogueEmpty}
      catalogueEmpty={{
        icon: KeyRound,
        title: "No API clients yet",
        description: "Register the first LSP API client to get started.",
      }}
      filterBar={
        <ApiClientsFilterBar filters={filters} onChange={setFilters} lspOptions={lspOptions} />
      }
      table={
        <ApiClientsTable
          data={list.data}
          isLoading={listLoading}
          filters={filters}
          onFiltersChange={setFilters}
          onEdit={openEdit}
          onRotate={openRotate}
        />
      }
      dialogs={
        <>
          <ApiClientCreateDialog
            open={createOpen}
            onOpenChange={handleCreateOpenChange}
            lspOptions={lspOptions}
            onCreate={handleCreate}
            onSecretAcknowledge={() => setRevealedSecret(null)}
            loading={create.isPending}
            errorMessage={create.isError ? extractAdminErrorMessage(create.error) : null}
          />

          <ApiClientEditDialog
            open={editTarget !== null}
            onOpenChange={handleEditOpenChange}
            client={editTarget}
            onSave={handleEditSave}
            loading={update.isPending}
            errorMessage={update.isError ? extractAdminErrorMessage(update.error) : null}
          />

          <RotateSecretDialog
            open={rotateTarget !== null}
            onOpenChange={handleRotateOpenChange}
            clientLabel={rotateTarget?.name}
            onConfirm={handleRotateConfirm}
            loading={rotate.isPending}
            errorMessage={rotate.isError ? extractAdminErrorMessage(rotate.error) : null}
          />
        </>
      }
    />
  );
}

export default ApiClientsPage;
export const Component = ApiClientsPage;
