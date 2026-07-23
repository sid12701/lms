import type { ColumnDef } from "@tanstack/react-table";
import { Badge } from "@/components/ui/badge";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { MisPreviewRow } from "../types";
import {
  installmentCell,
  nullableNumber,
  plainText,
  safeAadhaarDisplay,
  safeBankAccountDisplay,
  truncateMiddle,
} from "./mis-preview-helpers";

function buildMisPreviewColumns(maxInstallments: number): ColumnDef<MisPreviewRow>[] {
  return [
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
        <span className="text-foreground-muted text-xs">{plainText(row.original.productName)}</span>
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
          {(row.original.loanStatusDisplay ?? row.original.status).replace(/_/g, " ").toLowerCase()}
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
          {row.original.emiAmount > 0 ? formatINR(row.original.emiAmount, { compact: true }) : "—"}
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
        <span className="text-foreground tabular-nums">{row.original.interestPct.toFixed(2)}</span>
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
          className="text-foreground-muted max-w-48 truncate text-[11px]"
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
        <span className="text-foreground-muted text-[11px]">{plainText(row.original.gender)}</span>
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
  ];
}

const SUMMARY_COLUMN_KEYS = new Set([
  "loanId",
  "borrowerName",
  "lspName",
  "productName",
  "amount",
  "status",
  "dpd",
  "overdueAmount",
  "emiAmount",
  "disbursalDate",
]);

/** Curated preview columns — full export is available via report generation. */
export function buildMisPreviewSummaryColumns(): ColumnDef<MisPreviewRow>[] {
  return buildMisPreviewColumns(0).filter((column) => {
    const key =
      "accessorKey" in column && typeof column.accessorKey === "string"
        ? column.accessorKey
        : column.id;
    return key != null && SUMMARY_COLUMN_KEYS.has(String(key));
  });
}
