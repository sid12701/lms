import { useMemo } from "react";
import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from "@tanstack/react-table";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import type { RepaymentInstallment } from "@/schemas/loan-account";
import { InstallmentRow } from "./InstallmentRow";

export interface ScheduleTableProps {
  /** Ordered installment list (typically by `number` ascending). */
  installments: RepaymentInstallment[];
  /** Click handler forwarded to the next-due row. */
  onRecordPayment?: (installment: RepaymentInstallment) => void;
  /** Override the auto-detected next-due row id. */
  nextDueInstallmentId?: string;
  /** Optional aria-label for the table. */
  ariaLabel?: string;
  className?: string;
}

const COMPACT_HEAD = "h-8 px-2.5 text-[11px]";

/** Header columns — labels mirror the cells rendered by `InstallmentRow`. */
const COLUMNS: ColumnDef<RepaymentInstallment>[] = [
  { id: "number", header: "#" },
  { id: "dueDate", header: "Due date" },
  { id: "principal", header: "Principal", meta: { numeric: true } },
  { id: "interest", header: "Interest", meta: { numeric: true } },
  { id: "amount", header: "Installment", meta: { numeric: true } },
  { id: "outstanding", header: "Outstanding", meta: { numeric: true } },
  { id: "status", header: "Status" },
  // Action column header has no visible text — provide a screen-reader-only
  // label so axe's empty-table-header rule passes.
  { id: "action", header: () => <span className="sr-only">Actions</span> },
];

/**
 * Repayment schedule table — compact density per D7.
 *
 * Wraps the shadcn `<Table>` primitives. Uses TanStack's row model purely to
 * resolve a stable row ordering; cell rendering is delegated to
 * `InstallmentRow` so the action-button visibility logic lives next to the
 * row presentation.
 */
export function ScheduleTable({
  installments,
  onRecordPayment,
  nextDueInstallmentId,
  ariaLabel = "Repayment schedule",
  className,
}: ScheduleTableProps) {
  // Auto-detect the next-due row if the consumer did not pin one. The first
  // installment whose status is not `PAID` is considered next-due.
  const resolvedNextDueId = useMemo<string | undefined>(() => {
    if (nextDueInstallmentId) return nextDueInstallmentId;
    const firstUnpaid = installments.find((i) => i.status !== "PAID");
    return firstUnpaid?.id;
  }, [installments, nextDueInstallmentId]);

  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Table is the project-mandated table primitive
  const table = useReactTable({
    data: installments,
    columns: COLUMNS,
    getRowId: (row) => row.id,
    getCoreRowModel: getCoreRowModel(),
  });

  const rows = table.getRowModel().rows;

  return (
    <div
      data-slot="schedule-table"
      data-density="compact"
      className={cn(
        "border-border bg-surface shadow-e1 overflow-hidden rounded-md border",
        className,
      )}
    >
      <Table aria-label={ariaLabel}>
        <TableHeader className="bg-surface-muted/60">
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id} className="border-border hover:bg-transparent">
              {headerGroup.headers.map((header) => {
                const meta = header.column.columnDef.meta;
                return (
                  <TableHead
                    key={header.id}
                    scope="col"
                    className={cn(COMPACT_HEAD, meta?.numeric && "text-right")}
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
          {rows.length === 0 ? (
            <TableRow className="border-border hover:bg-transparent">
              <TableCell
                colSpan={COLUMNS.length}
                className="text-foreground-muted py-12 text-center text-sm"
              >
                No installments scheduled.
              </TableCell>
            </TableRow>
          ) : (
            rows.map((row) => (
              <InstallmentRow
                key={row.id}
                installment={row.original}
                outstandingAmount={row.original.outstandingAmount}
                isNextDue={row.original.id === resolvedNextDueId}
                onRecordPayment={onRecordPayment}
              />
            ))
          )}
        </TableBody>
      </Table>
    </div>
  );
}
