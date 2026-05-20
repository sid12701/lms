/**
 * Phase 9 — `/products` admin surface (SYSTEM_ADMIN or PRODUCT_ADMIN).
 *
 * Composes:
 *   - `ProductsFilterBar` (URL-bound filters via `useSearchParams`)
 *   - `ProductsTable` (server-paged TanStack table with row actions)
 *   - `ProductCreateDialog` (POST /api/v1/admin/products)
 *   - `ProductEditDialog`   (PATCH /api/v1/admin/products/:id)
 *   - `ProductMappingDialog`(PUT   /api/v1/admin/products/:id/mapping)
 *
 * Mutations append a `ProductAuditEvent` (BR-7) server-side. Role enforcement
 * is server-side AND client-side (router-level `RequireRole`). When a
 * non-permitted user lands here we surface a friendly EmptyState.
 *
 * Density default = comfortable per D7.
 */
import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Layers, Plus, ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { Button } from "@/components/ui/button";
import { ProductsFilterBar } from "./components/ProductsFilterBar";
import { ProductsTable } from "./components/ProductsTable";
import { ProductCreateDialog } from "./components/ProductCreateDialog";
import { ProductEditDialog } from "./components/ProductEditDialog";
import { ProductMappingDialog } from "./components/ProductMappingDialog";
import { useProducts } from "./hooks/useProducts";
import { useProduct } from "./hooks/useProduct";
import { useCreateProduct } from "./hooks/useCreateProduct";
import { useUpdateProduct } from "./hooks/useUpdateProduct";
import { useUpdateProductMapping } from "./hooks/useUpdateProductMapping";
import { useLspChoices } from "./hooks/useLspChoices";
import type {
  CreateProductInput,
  ProductRow,
  ProductsListFilters,
  UpdateProductInput,
  UpdateProductMappingInput,
} from "./types";
import type { z } from "zod";
import { ProductStatus } from "@/schemas/product";

type ProductStatusValue = z.infer<typeof ProductStatus>;

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

const VALID_STATUSES: readonly ProductStatusValue[] = ["ACTIVE", "INACTIVE"];

function parseFiltersFromUrl(params: URLSearchParams): ProductsListFilters {
  const filters: ProductsListFilters = {};
  const status = params.get("status");
  if (status && (VALID_STATUSES as readonly string[]).includes(status)) {
    filters.status = status as ProductStatusValue;
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

function filtersToParams(filters: ProductsListFilters): URLSearchParams {
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

export function ProductsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(
    () => parseFiltersFromUrl(searchParams),
    [searchParams],
  );

  const setFilters = (next: ProductsListFilters) => {
    setSearchParams(filtersToParams(next), { replace: false });
  };

  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ProductRow | null>(null);
  const [mappingTarget, setMappingTarget] = useState<ProductRow | null>(null);

  const list = useProducts(filters);
  const lspChoices = useLspChoices();
  const create = useCreateProduct();
  const update = useUpdateProduct();
  const updateMapping = useUpdateProductMapping();
  const mappingDetail = useProduct(mappingTarget?.id ?? null);

  const initialLspIds = useMemo<readonly string[]>(() => {
    if (mappingDetail.data?.mapping.lspIds) {
      return mappingDetail.data.mapping.lspIds;
    }
    return mappingTarget?.lspIds ?? [];
  }, [mappingDetail.data, mappingTarget]);

  // ── Create dialog handlers ──────────────────────────────────────────────────
  const handleCreateOpenChange = (open: boolean) => {
    if (!open) {
      if (create.isPending) return;
      setCreateOpen(false);
      create.reset();
    } else {
      setCreateOpen(true);
    }
  };
  const handleCreateConfirm = async ({ input }: { input: CreateProductInput }) => {
    try {
      await create.mutateAsync(input);
      setCreateOpen(false);
      create.reset();
    } catch {
      // Surfaced via create.error.
    }
  };

  // ── Edit dialog handlers ────────────────────────────────────────────────────
  const handleEditOpenChange = (open: boolean) => {
    if (!open) {
      if (update.isPending) return;
      setEditTarget(null);
      update.reset();
    }
  };
  const handleEditConfirm = async ({ input }: { input: UpdateProductInput }) => {
    if (!editTarget) return;
    try {
      await update.mutateAsync({ id: editTarget.id, ...input });
      setEditTarget(null);
      update.reset();
    } catch {
      // Surfaced via update.error.
    }
  };

  // ── Mapping dialog handlers ─────────────────────────────────────────────────
  const handleMappingOpenChange = (open: boolean) => {
    if (!open) {
      if (updateMapping.isPending) return;
      setMappingTarget(null);
      updateMapping.reset();
    }
  };
  const handleMappingConfirm = async ({
    input,
  }: {
    input: UpdateProductMappingInput;
  }) => {
    if (!mappingTarget) return;
    try {
      await updateMapping.mutateAsync({ id: mappingTarget.id, ...input });
      setMappingTarget(null);
      updateMapping.reset();
    } catch {
      // Surfaced via updateMapping.error.
    }
  };

  return (
    <div
      data-testid="products-page"
      className="flex flex-col gap-6 p-6"
      data-density="comfortable"
    >
      <PageHeader
        eyebrow="Administration"
        title="Products"
        description="Loan products, tenor and rate bands, and per-LSP availability."
        actions={
          <Button
            type="button"
            onClick={() => setCreateOpen(true)}
            data-slot="products-new-button"
          >
            <Plus aria-hidden="true" className="size-4" />
            New product
          </Button>
        }
      />

      {list.isError && isUnauthorized(list.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to products"
          description="The product admin surface is restricted to system or product administrators."
        />
      ) : list.isError ? (
        <ErrorState
          title="Couldn't load products"
          description="The product list couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void list.refetch();
            },
          }}
        />
      ) : (
        <>
          <ProductsFilterBar filters={filters} onChange={setFilters} />
          {!list.isPending &&
          (list.data?.total ?? 0) === 0 &&
          !filters.q &&
          !filters.status ? (
            <EmptyState
              icon={Layers}
              title="No products yet"
              description="Add the first loan product to get started."
            />
          ) : (
            <ProductsTable
              data={list.data}
              isLoading={list.isPending}
              filters={filters}
              onFiltersChange={setFilters}
              onEdit={(row) => setEditTarget(row)}
              onEditMapping={(row) => setMappingTarget(row)}
            />
          )}
        </>
      )}

      <ProductCreateDialog
        open={createOpen}
        onOpenChange={handleCreateOpenChange}
        lspChoices={lspChoices}
        onConfirm={handleCreateConfirm}
        loading={create.isPending}
        errorMessage={create.isError ? extractErrorMessage(create.error) : null}
      />

      <ProductEditDialog
        open={editTarget !== null}
        onOpenChange={handleEditOpenChange}
        product={editTarget}
        onConfirm={handleEditConfirm}
        loading={update.isPending}
        errorMessage={update.isError ? extractErrorMessage(update.error) : null}
      />

      <ProductMappingDialog
        open={mappingTarget !== null}
        onOpenChange={handleMappingOpenChange}
        product={mappingTarget}
        initialLspIds={initialLspIds}
        lspChoices={lspChoices}
        onConfirm={handleMappingConfirm}
        loading={updateMapping.isPending}
        errorMessage={
          updateMapping.isError ? extractErrorMessage(updateMapping.error) : null
        }
      />
    </div>
  );
}

export default ProductsPage;
export const Component = ProductsPage;
