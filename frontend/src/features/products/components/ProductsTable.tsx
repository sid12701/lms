/**
 * `ProductsTable` — TanStack-table view of the loan products catalog.
 *
 * Columns: Code, Name, Status, Principal range, Interest %, Tenure range,
 * LSPs, Actions (Edit, Edit mapping). Row click → opens edit dialog.
 */
import { useCallback, useMemo } from "react";
import { Edit2, Layers, Network } from "lucide-react";
import type { ColumnDef, PaginationState } from "@tanstack/react-table";
import { AdminEntityDataTable } from "@/components/app/data/AdminEntityDataTable";
import { EntityRowActions } from "@/components/app/data/EntityRowActions";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { ProductRow, ProductsListFilters, ProductsListResponse } from "../types";

export interface ProductsTableProps {
  data: ProductsListResponse | undefined;
  isLoading: boolean;
  filters: ProductsListFilters;
  onFiltersChange: (next: ProductsListFilters) => void;
  onEdit: (row: ProductRow) => void;
  onEditMapping: (row: ProductRow) => void;
  className?: string;
}

function formatPrincipalRange(min: number, max: number): string {
  return `${formatINR(min, { compact: true })} – ${formatINR(max, { compact: true })}`;
}

function formatTenureRange(min: number, max: number): string {
  return `${min}–${max} months`;
}

export function ProductsTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  onEdit,
  onEditMapping,
  className,
}: ProductsTableProps) {
  const rows = data?.items ?? [];
  const total = data?.total ?? 0;
  const pageSize = filters.pageSize ?? 25;
  const page = filters.page ?? 0;

  const columns = useMemo<ColumnDef<ProductRow>[]>(
    () => [
      {
        id: "code",
        header: "Code",
        meta: { label: "Code", mobileCard: "primary" },
        cell: ({ row }) => (
          <span data-slot="products-code" className="text-foreground font-mono text-sm font-medium">
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Name",
        meta: { label: "Name", mobileCard: "secondary" },
        cell: ({ row }) => (
          <div className="flex flex-col gap-0.5">
            <span className="text-foreground text-sm font-medium">{row.original.name}</span>
            <span className="text-foreground-muted text-xs">
              {row.original.lspNames.length === 0
                ? "Not mapped to any LSP"
                : `Available to ${row.original.lspNames.length} LSP${
                    row.original.lspNames.length === 1 ? "" : "s"
                  }`}
            </span>
          </div>
        ),
      },
      {
        id: "status",
        header: "Status",
        meta: { label: "Status", mobileCard: "primary" },
        cell: ({ row }) => {
          const isActive = row.original.status === "ACTIVE";
          return (
            <Badge
              data-slot="products-status-badge"
              data-status={row.original.status}
              className={cn(
                "gap-1",
                isActive
                  ? "border-success/30 bg-success/10 text-success"
                  : "border-border bg-surface-muted text-foreground-muted",
              )}
            >
              {isActive ? "Active" : "Inactive"}
            </Badge>
          );
        },
      },
      {
        id: "principal",
        header: "Principal range",
        meta: { numeric: true, label: "Principal range", mobileCard: "secondary" },
        cell: ({ row }) => (
          <span className="text-foreground text-sm tabular-nums">
            {formatPrincipalRange(row.original.principalMin, row.original.principalMax)}
          </span>
        ),
      },
      {
        id: "interest",
        header: "Interest",
        meta: { numeric: true },
        cell: ({ row }) => (
          <span className="text-foreground text-sm tabular-nums">
            {row.original.interestRatePct.toFixed(2)}%
          </span>
        ),
      },
      {
        id: "tenure",
        header: "Tenure",
        meta: { numeric: true },
        cell: ({ row }) => (
          <span className="text-foreground text-sm tabular-nums">
            {formatTenureRange(row.original.tenureMinMonths, row.original.tenureMaxMonths)}
          </span>
        ),
      },
      {
        id: "actions",
        header: () => <span className="sr-only">Actions</span>,
        meta: { mobileCard: "actions" },
        cell: ({ row }) => {
          const r = row.original;
          return (
            <EntityRowActions
              mode="menu"
              ariaLabel={`Actions for ${r.code}`}
              triggerDataSlot="products-row-menu"
              items={[
                {
                  id: "edit",
                  label: "Edit product",
                  icon: Edit2,
                  dataSlot: "products-action-edit",
                  onSelect: () => onEdit(r),
                },
                {
                  id: "mapping",
                  label: "Edit LSP mapping",
                  icon: Network,
                  dataSlot: "products-action-mapping",
                  onSelect: () => onEditMapping(r),
                },
              ]}
            />
          );
        },
      },
    ],
    [onEdit, onEditMapping],
  );

  const handlePaginationChange = useCallback(
    (next: PaginationState) => {
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
    [filters, onFiltersChange],
  );

  return (
    <AdminEntityDataTable
      dataSlot="products-table"
      className={className}
      columns={columns}
      rows={rows}
      total={total}
      page={page}
      pageSize={pageSize}
      loading={isLoading}
      rowIdKey="id"
      ariaLabel="Loan products"
      onPaginationChange={handlePaginationChange}
      getRowAction={(row) => onEdit(row.original)}
      empty={
        <EmptyState
          variant="filtered-empty"
          icon={Layers}
          title="No products"
          description="Loosen the filters or create a new loan product."
        />
      }
    />
  );
}
