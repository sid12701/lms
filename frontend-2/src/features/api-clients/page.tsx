/**
 * Phase 9 — `/api-clients` admin surface (SYSTEM_ADMIN-only).
 *
 * Composes:
 *   - `ApiClientsFilterBar` (URL-bound filters via `useSearchParams`)
 *   - `ApiClientsTable` (server-paged TanStack table with a "Manage" action)
 *   - `ApiClientCreateDialog` (POST /api/v1/admin/api-clients)
 *   - Shared `RotateSecretDialog` from `@/components/app/secrets/...`
 *   - Shared `ApiSecretReveal` banner (renders the cleartext secret exactly
 *     once — surfaced both on create and on rotate)
 *
 * Role enforcement is server-side (mock handler 401s non-admin) AND
 * client-side (router-level RequireRole). When a non-admin somehow lands
 * here we surface a friendly EmptyState instead of an ErrorState — same
 * pattern as the LSPs page.
 *
 * Density default = comfortable per D7 (admin list is short).
 *
 * NOTE: An `ApiClientEditDialog` does not exist in `./components/` today,
 * so this page does not surface an edit modal. The table's "Manage" action
 * routes to the shared rotate-secret flow — the primary admin operation
 * available against an existing client. Edit-name / edit-status /
 * edit-IP-allow-list will land alongside their dialog component later.
 */
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { KeyRound, Plus, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Button } from "@/components/ui/button";
import {
  ApiSecretReveal,
  RotateSecretDialog,
} from "@/components/app/secrets";
import { ApiClientsFilterBar } from "./components/ApiClientsFilterBar";
import { ApiClientsTable } from "./components/ApiClientsTable";
import { ApiClientCreateDialog } from "./components/ApiClientCreateDialog";
import { useApiClients } from "./hooks/useApiClients";
import { useCreateApiClient } from "./hooks/useCreateApiClient";
import { useRotateApiClientSecret } from "./hooks/useRotateApiClientSecret";
import { useLsps } from "@/features/lsps/hooks/useLsps";
import type { ApiClientRow, ApiClientsListFilters } from "./types";
import type { ApiClientStatus } from "@/schemas/user";
import { z } from "zod";

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

const VALID_STATUSES: readonly ApiClientStatus[] = ["ACTIVE", "DISABLED"];

const UuidSchema = z.string().uuid();

function parseFiltersFromUrl(params: URLSearchParams): ApiClientsListFilters {
  const filters: ApiClientsListFilters = {};
  const status = params.get("status");
  if (status && (VALID_STATUSES as readonly string[]).includes(status)) {
    filters.status = status as ApiClientStatus;
  }
  const lspId = params.get("lspId");
  if (lspId && UuidSchema.safeParse(lspId).success) {
    filters.lspId = lspId;
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

function filtersToParams(filters: ApiClientsListFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.status) params.set("status", filters.status);
  if (filters.lspId) params.set("lspId", filters.lspId);
  if (filters.q) params.set("q", filters.q);
  if (typeof filters.page === "number" && filters.page > 0) {
    params.set("page", String(filters.page));
  }
  if (typeof filters.pageSize === "number") {
    params.set("pageSize", String(filters.pageSize));
  }
  return params;
}

export function ApiClientsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(
    () => parseFiltersFromUrl(searchParams),
    [searchParams],
  );

  const setFilters = (next: ApiClientsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [createOpen, setCreateOpen] = useState(false);
  const [rotateTarget, setRotateTarget] = useState<ApiClientRow | null>(null);
  const [revealedSecret, setRevealedSecret] = useState<{
    clientName: string;
    secret: string;
  } | null>(null);

  const list = useApiClients(filters);
  const create = useCreateApiClient();
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

  // ── Create dialog handlers ─────────────────────────────────────────────────
  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setCreateOpen(false);
      create.reset();
    } else {
      setCreateOpen(true);
    }
  };
  const handleCreate = async (input: {
    name: string;
    lspId: string;
    ipAllowList: string[];
    idempotencyKey: string;
  }) => {
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

  // ── Rotate dialog handlers ─────────────────────────────────────────────────
  const handleRotateOpenChange = (open: boolean) => {
    if (!open) {
      if (rotate.isPending) return;
      setRotateTarget(null);
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
    try {
      const res = await rotate.mutateAsync({
        id: rotateTarget.id,
        reason,
        idempotencyKey,
      });
      setRevealedSecret({
        clientName: res.client.name,
        secret: res.clientSecret,
      });
      setRotateTarget(null);
      rotate.reset();
    } catch {
      // Surfaced via `rotate.error` — kept inside the dialog while the
      // operator decides whether to retry or cancel.
    }
  };

  return (
    <div
      data-testid="api-clients-page"
      className="flex flex-col gap-6 p-6"
      data-density="comfortable"
    >
      <PageHeader
        eyebrow="Administration"
        title="API clients"
        description="LSP API credentials, IP allow-lists, and rotation history."
        actions={
          <Button
            type="button"
            onClick={() => setCreateOpen(true)}
            data-slot="api-clients-new-button"
          >
            <Plus aria-hidden="true" className="size-4" />
            New API client
          </Button>
        }
      />

      {revealedSecret && !createOpen ? (
        <ApiSecretReveal
          secret={revealedSecret.secret}
          clientLabel={revealedSecret.clientName}
          onAcknowledge={() => setRevealedSecret(null)}
        />
      ) : null}

      {list.isError && isUnauthorized(list.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to API clients"
          description="The API client admin surface is restricted to system administrators."
        />
      ) : list.isError ? (
        <ErrorState
          title="Couldn't load API clients"
          description="The API client list couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void list.refetch();
            },
          }}
        />
      ) : (
        <>
          <ApiClientsFilterBar
            filters={filters}
            onChange={setFilters}
            lspOptions={lspOptions}
          />
          {!list.isPending &&
          (list.data?.total ?? 0) === 0 &&
          !filters.q &&
          !filters.status &&
          !filters.lspId ? (
            <EmptyState
              icon={KeyRound}
              title="No API clients yet"
              description="Register the first LSP API client to get started."
            />
          ) : (
            <ApiClientsTable
              data={list.data}
              isLoading={list.isPending}
              filters={filters}
              onFiltersChange={setFilters}
              onSelect={(row) => setRotateTarget(row)}
            />
          )}
        </>
      )}

      <ApiClientCreateDialog
        open={createOpen}
        onOpenChange={handleCreateOpenChange}
        lspOptions={lspOptions}
        onCreate={handleCreate}
        onSecretAcknowledge={() => setRevealedSecret(null)}
        loading={create.isPending}
        errorMessage={
          create.isError ? extractErrorMessage(create.error) : null
        }
      />

      <RotateSecretDialog
        open={rotateTarget !== null}
        onOpenChange={handleRotateOpenChange}
        clientLabel={rotateTarget?.name}
        onConfirm={handleRotateConfirm}
        loading={rotate.isPending}
      />
    </div>
  );
}

export default ApiClientsPage;
export const Component = ApiClientsPage;
