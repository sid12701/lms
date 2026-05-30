/**
 * `ProductsTable` — TanStack-table view of the loan products catalog.
 *
 * Columns: Code, Name, Status, Principal range, Interest %, Tenure range,
 * LSPs, Actions (Edit, Edit mapping). Row click → opens edit dialog.
 */
import { useMemo } from "react";
import { Edit2, Layers, MoreHorizontal, Network } from "lucide-react";
import {
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type PaginationState,
} from "@tanstack/react-table";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { DataTable } from "@/components/app/data/DataTable";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
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
        cell: ({ row }) => (
          <span data-slot="products-code" className="text-foreground font-mono text-sm font-medium">
            {row.original.code}
          </span>
        ),
      },
      {
        id: "name",
        header: "Name",
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
        meta: { numeric: true },
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
        cell: ({ row }) => {
          const r = row.original;
          return (
            <Popover>
              <PopoverTrigger asChild>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  data-slot="products-row-menu"
                  aria-label={`Actions for ${r.code}`}
                  onClick={(e) => e.stopPropagation()}
                >
                  <MoreHorizontal aria-hidden="true" className="size-4" />
                </Button>
              </PopoverTrigger>
              <PopoverContent align="end" className="w-48 p-1">
                <ul className="flex flex-col">
                  <li>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      data-slot="products-action-edit"
                      onClick={(e) => {
                        e.stopPropagation();
                        onEdit(r);
                      }}
                      className="w-full justify-start gap-2"
                    >
                      <Edit2 aria-hidden="true" className="size-4" />
                      Edit product
                    </Button>
                  </li>
                  <li>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      data-slot="products-action-mapping"
                      onClick={(e) => {
                        e.stopPropagation();
                        onEditMapping(r);
                      }}
                      className="w-full justify-start gap-2"
                    >
                      <Network aria-hidden="true" className="size-4" />
                      Edit LSP mapping
                    </Button>
                  </li>
                </ul>
              </PopoverContent>
            </Popover>
          );
        },
      },
    ],
    [onEdit, onEditMapping],
  );

  const pagination = useMemo<PaginationState>(
    () => ({ pageIndex: page, pageSize }),
    [page, pageSize],
  );

  const table = useReactTable({
    data: rows,
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: total,
    getRowId: (row) => row.id,
    getCoreRowModel: getCoreRowModel(),
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  return (
    <div data-slot="products-table" className={cn("flex flex-col gap-2", className)}>
      <DataTable<ProductRow, unknown>
        columns={columns}
        data={rows}
        loading={isLoading}
        rowIdKey="id"
        ariaLabel="Loan products"
        getRowAction={(row) => onEdit(row.original)}
        pagination={{
          manualPagination: true,
          rowCount: total,
          initialPageSize: pageSize,
        }}
        state={{
          pagination: { pageIndex: page, pageSize },
        }}
        onStateChange={(next) => {
          if (next.pagination) {
            onFiltersChange({
              ...filters,
              page: next.pagination.pageIndex,
              pageSize: next.pagination.pageSize,
            });
          }
        }}
        empty={
          <EmptyState
            variant="filtered-empty"
            icon={Layers}
            title="No products"
            description="Loosen the filters or create a new loan product."
          />
        }
      />
      <DataTablePagination table={table} totalRows={total} />
    </div>
  );
}
