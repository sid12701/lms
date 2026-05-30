/**
 * Compact-density preview table of the Portfolio MIS dataset.
 *
 * Density locked to "compact" per D7 (MIS preview is a long-list surface).
 * Server-paged: the parent owns `{page, pageSize}` filter state; pagination
 * change fires `onFiltersChange`.
 *
 * Gap #10 — the preview now exposes every column the BE returns. Identity +
 * status columns lead; financial + lifecycle dates follow; borrower KYC +
 * banking columns close out the row. Aadhaar values are pre-masked by the
 * BE (Gap #1 + Gap #10) and the column renders a defensive masker as a
 * second line of defence against a misconfigured upstream. The table
 * scrolls horizontally inside the preview card to keep the page chrome
 * stable.
 */
import { useMemo } from "react";
import {
  type ColumnDef,
  type PaginationState,
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { DataTablePagination } from "@/components/app/data/DataTablePagination";
import { TableSkeleton } from "@/components/app/feedback/Skeletons";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { formatINR, maskAadhaar } from "@/lib/format";
import { cn } from "@/lib/utils";
import type {
  MisPreviewFilters,
  MisPreviewInstallment,
  MisPreviewResponseDto,
  MisPreviewRow,
} from "../types";

function truncateMiddle(value: string, head = 6, tail = 4): string {
  if (value.length <= head + tail + 1) return value;
  return `${value.slice(0, head)}…${value.slice(-tail)}`;
}

function plainText(value: string | null | undefined): string {
  return value && value.trim() !== "" ? value : "—";
}

function nullableNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  if (!Number.isFinite(value)) return "—";
  return value.toLocaleString();
}

/**
 * Render an aadhaar value, defensively re-masking through the global
 * formatter. The BE masks before sending (Gap #1 + Gap #10); this is a
 * second line of defence so a misconfigured upstream cannot leak digits
 * through the preview surface.
 */
function safeAadhaarDisplay(value: string | null | undefined): string {
  if (!value) return "—";
  return maskAadhaar(value);
}

/**
 * Render the borrower bank account masked to last-4 — the BE already
 * masks to `XXXX<last4>` (Gap #10). This component preserves whatever the
 * wire returned and falls back to em-dash if absent.
 */
function safeBankAccountDisplay(value: string | null | undefined): string {
  if (!value) return "—";
  return value;
}

function installmentCell(installment: MisPreviewInstallment | undefined): string {
  if (!installment) return "—";
  const due = installment.dueDate ?? "—";
  const paid = formatINR(installment.paidAmount, { compact: true });
  const dueAmt = formatINR(installment.installmentAmount, { compact: true });
  return `${due} · ${paid}/${dueAmt}`;
}

export interface MisPreviewTableProps {
  data: MisPreviewResponseDto | undefined;
  isLoading: boolean;
  filters: MisPreviewFilters;
  onFiltersChange: (next: MisPreviewFilters) => void;
  className?: string;
}

export function MisPreviewTable({
  data,
  isLoading,
  filters,
  onFiltersChange,
  className,
}: MisPreviewTableProps) {
  const pageSize = filters.pageSize ?? 25;
  const pageIndex = filters.page ?? 0;

  const pagination: PaginationState = useMemo(
    () => ({ pageIndex, pageSize }),
    [pageIndex, pageSize],
  );

  const rows = data?.items ?? [];

  const maxInstallments = useMemo(() => {
    let max = 0;
    for (const row of rows) {
      const count = row.installments?.length ?? 0;
      if (count > max) max = count;
    }
    return max;
  }, [rows]);

  const columns = useMemo<ColumnDef<MisPreviewRow>[]>(
    () => [
      {
        accessorKey: "loanId",
        meta: { label: "Loan id" },
        header: () => <span>Loan id</span>,
        cell: ({ row }) => (
          <span className="text-foreground font-mono text-[11px]" title={row.original.loanId}>
            {truncateMiddle(row.original.loanId)}
          </span>
        ),
      },
      {
        accessorKey: "borrowerName",
        meta: { label: "Borrower" },
        header: () => <span>Borrower</span>,
        cell: ({ row }) => (
          <span className="text-foreground text-xs">{row.original.borrowerName}</span>
        ),
      },
      {
        accessorKey: "accountNumber",
        meta: { label: "Account" },
        header: () => <span>Account</span>,
        cell: ({ row }) => (
          <span className="text-foreground font-mono text-[11px]">
            {plainText(row.original.accountNumber)}
          </span>
        ),
      },
      {
        accessorKey: "lspCode",
        meta: { label: "LSP code" },
        header: () => <span>LSP code</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tracking-wide uppercase">
            {row.original.lspCode}
          </span>
        ),
      },
      {
        accessorKey: "lspName",
        meta: { label: "LSP" },
        header: () => <span>LSP</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">{plainText(row.original.lspName)}</span>
        ),
      },
      {
        accessorKey: "productCode",
        meta: { label: "Product code" },
        header: () => <span>Product code</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tracking-wide uppercase">
            {row.original.productCode}
          </span>
        ),
      },
      {
        accessorKey: "productName",
        meta: { label: "Product" },
        header: () => <span>Product</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-xs">
            {plainText(row.original.productName)}
          </span>
        ),
      },
      {
        accessorKey: "amount",
        meta: { label: "Amount", numeric: true },
        header: () => <span>Amount</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {formatINR(row.original.amount, { compact: true })}
          </span>
        ),
      },
      {
        accessorKey: "status",
        meta: { label: "Status" },
        header: () => <span>Status</span>,
        cell: ({ row }) => (
          <Badge
            variant="outline"
            data-status={row.original.status}
            className="text-[10px] font-medium"
          >
            {(row.original.loanStatusDisplay ?? row.original.status)
              .replace(/_/g, " ")
              .toLowerCase()}
          </Badge>
        ),
      },
      {
        accessorKey: "applicationCreatedAt",
        meta: { label: "Applied" },
        header: () => <span>Applied</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tabular-nums">
            {plainText(row.original.applicationCreatedAt)}
          </span>
        ),
      },
      {
        accessorKey: "disbursalDate",
        meta: { label: "Disbursed" },
        header: () => <span>Disbursed</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tabular-nums">
            {row.original.disbursalDate ?? "—"}
          </span>
        ),
      },
      {
        accessorKey: "dpd",
        meta: { label: "DPD", numeric: true },
        header: () => <span>DPD</span>,
        cell: ({ row }) => (
          <span
            className={cn(
              "tabular-nums",
              row.original.dpd > 0 ? "text-warning font-medium" : "text-foreground-muted",
            )}
          >
            {row.original.dpd}
          </span>
        ),
      },
      {
        accessorKey: "emiAmount",
        meta: { label: "EMI", numeric: true },
        header: () => <span>EMI</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {row.original.emiAmount > 0
              ? formatINR(row.original.emiAmount, { compact: true })
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "overdueAmount",
        meta: { label: "Overdue", numeric: true },
        header: () => <span>Overdue</span>,
        cell: ({ row }) => (
          <span
            className={cn(
              "tabular-nums",
              row.original.overdueAmount > 0 ? "text-danger font-medium" : "text-foreground-muted",
            )}
          >
            {row.original.overdueAmount > 0
              ? formatINR(row.original.overdueAmount, { compact: true })
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "delinquencyBucket",
        meta: { label: "Bucket" },
        header: () => <span>Bucket</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tracking-wide uppercase">
            {row.original.delinquencyBucket ?? "—"}
          </span>
        ),
      },
      {
        accessorKey: "externalLoanId",
        meta: { label: "External id" },
        header: () => <span>External id</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]">
            {row.original.externalLoanId ?? "—"}
          </span>
        ),
      },
      {
        accessorKey: "year",
        meta: { label: "Year", numeric: true },
        header: () => <span>Year</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">{row.original.year ?? "—"}</span>
        ),
      },
      {
        accessorKey: "disbursalAmount",
        meta: { label: "Disbursal", numeric: true },
        header: () => <span>Disbursal</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {formatINR(row.original.disbursalAmount, { compact: true })}
          </span>
        ),
      },
      {
        accessorKey: "processingFee",
        meta: { label: "Fee", numeric: true },
        header: () => <span>Fee</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {formatINR(row.original.processingFee, { compact: true })}
          </span>
        ),
      },
      {
        accessorKey: "interestPct",
        meta: { label: "Rate %", numeric: true },
        header: () => <span>Rate %</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {row.original.interestPct.toFixed(2)}
          </span>
        ),
      },
      {
        accessorKey: "tenureMonths",
        meta: { label: "Tenure", numeric: true },
        header: () => <span>Tenure</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">{row.original.tenureMonths}</span>
        ),
      },
      {
        accessorKey: "closureDate",
        meta: { label: "Closed" },
        header: () => <span>Closed</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tabular-nums">
            {plainText(row.original.closureDate)}
          </span>
        ),
      },
      {
        accessorKey: "closureReason",
        meta: { label: "Closure reason" },
        header: () => <span>Closure reason</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px]">
            {plainText(row.original.closureReason)}
          </span>
        ),
      },
      {
        accessorKey: "foreclosureDate",
        meta: { label: "Foreclosed" },
        header: () => <span>Foreclosed</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px] tabular-nums">
            {plainText(row.original.foreclosureDate)}
          </span>
        ),
      },
      {
        accessorKey: "foreclosedAmount",
        meta: { label: "Foreclosed ₹", numeric: true },
        header: () => <span>Foreclosed ₹</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {row.original.foreclosedAmount !== null
              ? formatINR(row.original.foreclosedAmount, { compact: true })
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "pan",
        meta: { label: "PAN" },
        header: () => <span>PAN</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]">
            {plainText(row.original.pan)}
          </span>
        ),
      },
      {
        accessorKey: "aadhaar",
        meta: { label: "Aadhaar" },
        header: () => <span>Aadhaar</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]" data-pii="aadhaar">
            {safeAadhaarDisplay(row.original.aadhaar)}
          </span>
        ),
      },
      {
        accessorKey: "borrowerId",
        meta: { label: "Borrower id" },
        header: () => <span>Borrower id</span>,
        cell: ({ row }) => (
          <span
            className="text-foreground-muted font-mono text-[11px]"
            title={row.original.borrowerId ?? undefined}
          >
            {row.original.borrowerId ? truncateMiddle(row.original.borrowerId) : "—"}
          </span>
        ),
      },
      {
        accessorKey: "address",
        meta: { label: "Address" },
        header: () => <span>Address</span>,
        cell: ({ row }) => (
          <span
            className="text-foreground-muted max-w-[12rem] truncate text-[11px]"
            title={row.original.address ?? undefined}
          >
            {plainText(row.original.address)}
          </span>
        ),
      },
      {
        accessorKey: "gender",
        meta: { label: "Gender" },
        header: () => <span>Gender</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px]">
            {plainText(row.original.gender)}
          </span>
        ),
      },
      {
        accessorKey: "state",
        meta: { label: "State" },
        header: () => <span>State</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px]">{plainText(row.original.state)}</span>
        ),
      },
      {
        accessorKey: "zip",
        meta: { label: "ZIP" },
        header: () => <span>ZIP</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]">
            {plainText(row.original.zip)}
          </span>
        ),
      },
      {
        accessorKey: "ifsc",
        meta: { label: "IFSC" },
        header: () => <span>IFSC</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]">
            {plainText(row.original.ifsc)}
          </span>
        ),
      },
      {
        accessorKey: "bankAccount",
        meta: { label: "Bank a/c" },
        header: () => <span>Bank a/c</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted font-mono text-[11px]" data-pii="bank-account">
            {safeBankAccountDisplay(row.original.bankAccount)}
          </span>
        ),
      },
      {
        accessorKey: "profession",
        meta: { label: "Profession" },
        header: () => <span>Profession</span>,
        cell: ({ row }) => (
          <span className="text-foreground-muted text-[11px]">
            {plainText(row.original.profession)}
          </span>
        ),
      },
      {
        accessorKey: "income",
        meta: { label: "Income", numeric: true },
        header: () => <span>Income</span>,
        cell: ({ row }) => (
          <span className="text-foreground tabular-nums">
            {row.original.income !== null
              ? formatINR(row.original.income, { compact: true })
              : nullableNumber(row.original.income)}
          </span>
        ),
      },
      ...Array.from({ length: maxInstallments }, (_, index) => {
        const installmentNumber = index + 1;
        return {
          id: `emi-${installmentNumber}`,
          meta: { label: `EMI ${installmentNumber}` },
          header: () => <span>{`EMI ${installmentNumber}`}</span>,
          cell: ({ row }: { row: { original: MisPreviewRow } }) => {
            const installment = row.original.installments?.find(
              (inst) => inst.installmentNumber === installmentNumber,
            );
            return (
              <span
                className={cn(
                  "text-foreground-muted text-[11px] whitespace-nowrap",
                  installment?.received && "text-success",
                )}
              >
                {installmentCell(installment)}
              </span>
            );
          },
        } satisfies ColumnDef<MisPreviewRow>;
      }),
    ],
    [maxInstallments],
  );

  const table = useReactTable({
    data: rows as MisPreviewRow[],
    columns,
    state: { pagination },
    manualPagination: true,
    rowCount: data?.total ?? 0,
    getCoreRowModel: getCoreRowModel(),
    getRowId: (row) => row.loanId,
    onPaginationChange: (updater) => {
      const next = typeof updater === "function" ? updater(pagination) : updater;
      onFiltersChange({
        ...filters,
        page: next.pageIndex,
        pageSize: next.pageSize,
      });
    },
  });

  const visibleColumnCount = table.getVisibleLeafColumns().length;
  const tableRows = table.getRowModel().rows;
  const isEmpty = !isLoading && rows.length === 0;

  if (isLoading && rows.length === 0) {
    return (
      <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
        <TableSkeleton rows={8} cols={12} />
      </div>
    );
  }

  return (
    <div data-slot="mis-preview-table" className={cn("flex flex-col gap-3", className)}>
      <div
        className="border-border bg-surface shadow-e1 overflow-x-auto rounded-md border"
        data-density="compact"
        data-slot="mis-preview-scroll"
      >
        <Table aria-label="Portfolio MIS preview" className="min-w-max">
          <TableHeader className="bg-surface-muted/60 sticky top-0 z-10 backdrop-blur">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
                {headerGroup.headers.map((header) => {
                  const numeric = header.column.columnDef.meta?.numeric ?? false;
                  return (
                    <TableHead
                      key={header.id}
                      scope="col"
                      className={cn(
                        "text-foreground-muted h-8 px-2.5 text-[11px] font-medium tracking-wide uppercase",
                        numeric && "text-right",
                      )}
                    >
                      {header.isPlaceholder
                        ? null
                        : flexRender(header.column.columnDef.header, header.getContext())}
                    </TableHead>
                  );
                })}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {isEmpty ? (
              <TableRow className="border-border hover:bg-transparent">
                <TableCell colSpan={visibleColumnCount} className="p-0">
                  <EmptyState
                    variant="filtered-empty"
                    title="No portfolio rows match these filters."
                    description="Widen the date range or clear filters to see more."
                  />
                </TableCell>
              </TableRow>
            ) : (
              tableRows.map((row) => (
                <TableRow
                  key={row.id}
                  data-testid={`mis-preview-row-${row.original.loanId}`}
                  className="border-border"
                >
                  {row.getVisibleCells().map((cell) => {
                    const numeric = cell.column.columnDef.meta?.numeric ?? false;
                    return (
                      <TableCell
                        key={cell.id}
                        {...(numeric ? TABULAR_ATTR : {})}
                        className={cn(
                          "text-foreground px-2.5 py-1.5 text-xs",
                          numeric && "text-right font-mono",
                        )}
                      >
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </TableCell>
                    );
                  })}
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      <DataTablePagination table={table} totalRows={data?.total ?? 0} className="px-1" />
    </div>
  );
}
