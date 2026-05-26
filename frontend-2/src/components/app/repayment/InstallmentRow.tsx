import { memo } from "react";
import { AlertTriangle, CheckCircle2, CircleDot, Hourglass } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { formatINR, formatDate } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { RepaymentInstallment, InstallmentStatus } from "@/schemas/loan-account";

export interface InstallmentRowProps {
  /** The installment row payload — Zod-inferred shape from `loan-account.ts`. */
  installment: RepaymentInstallment;
  /** Outstanding amount still owed against this installment. */
  outstandingAmount: number;
  /** Whether this row is the single installment currently eligible for repayment. */
  isNextDue?: boolean;
  /** Fired when the action button is clicked. */
  onRecordPayment?: (installment: RepaymentInstallment) => void;
}

interface StatusVisual {
  label: string;
  icon: typeof CircleDot;
  className: string;
}

/**
 * Status presentation lookup. Color is paired with icon + text so the status
 * is never communicated by colour alone (WCAG 1.4.1).
 */
const STATUS_VISUAL: Record<InstallmentStatus, StatusVisual> = {
  DUE: {
    label: "Due",
    icon: Hourglass,
    className: "border-progress/30 bg-progress/10 text-progress",
  },
  PARTIALLY_PAID: {
    label: "Partially paid",
    icon: CircleDot,
    className: "border-warning/30 bg-warning/10 text-warning",
  },
  PAID: {
    label: "Paid",
    icon: CheckCircle2,
    className: "border-success/30 bg-success/10 text-success",
  },
  OVERDUE: {
    label: "Overdue",
    icon: AlertTriangle,
    className: "border-danger/30 bg-danger/10 text-danger",
  },
};

/**
 * Single installment row — pure presentational. Rendered inside a TanStack
 * `<tbody>` produced by `ScheduleTable`. Numeric cells opt in to tabular
 * figures via `TABULAR_ATTR` so columns of money align.
 */
function InstallmentRowImpl({
  installment,
  outstandingAmount,
  isNextDue = false,
  onRecordPayment,
}: InstallmentRowProps) {
  const visual = STATUS_VISUAL[installment.status];
  const Icon = visual.icon;
  const showAction =
    Boolean(onRecordPayment)
    && isNextDue
    && installment.status !== "PAID"
    && outstandingAmount > 0;

  return (
    <TableRow data-slot="installment-row" data-status={installment.status}>
      <TableCell className="text-foreground-muted py-2 text-sm" {...TABULAR_ATTR}>
        {installment.number}
      </TableCell>
      <TableCell className="text-foreground py-2 text-sm">
        {formatDate(installment.dueDate)}
        {installment.dpd > 0 ? (
          <span
            className="text-danger ml-2 text-xs font-medium"
            aria-label={`${installment.dpd} days past due`}
          >
            +{installment.dpd}d
          </span>
        ) : null}
      </TableCell>
      <TableCell className="py-2 text-right text-sm" {...TABULAR_ATTR}>
        {formatINR(installment.principalDue, { decimals: 2 })}
      </TableCell>
      <TableCell className="py-2 text-right text-sm" {...TABULAR_ATTR}>
        {formatINR(installment.interestDue, { decimals: 2 })}
      </TableCell>
      <TableCell
        className="py-2 text-right text-sm font-medium"
        {...TABULAR_ATTR}
      >
        {formatINR(installment.installmentAmount, { decimals: 2 })}
      </TableCell>
      <TableCell className="py-2 text-right text-sm" {...TABULAR_ATTR}>
        {formatINR(outstandingAmount, { decimals: 2 })}
      </TableCell>
      <TableCell className="py-2 text-sm">
        <Badge
          data-slot="installment-status-badge"
          className={cn("gap-1", visual.className)}
        >
          <Icon aria-hidden="true" className="size-3" />
          <span>{visual.label}</span>
        </Badge>
      </TableCell>
      <TableCell className="py-2 text-right text-sm">
        {showAction ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => onRecordPayment?.(installment)}
            aria-label={`Record payment for installment ${installment.number}`}
          >
            Record payment
          </Button>
        ) : null}
      </TableCell>
    </TableRow>
  );
}

export const InstallmentRow = memo(InstallmentRowImpl);
