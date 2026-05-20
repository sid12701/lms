import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatINR, formatDate } from "@/lib/format";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";

export interface DisbursementSummaryCardProps {
  approvedPrincipal: number;
  tenureMonths: number;
  interestRatePercent: number;
  /** ISO date string for the scheduled disbursement. */
  scheduledOn: string;
  /** ISO date string for the computed first EMI due date. */
  firstEmiDate: string;
  className?: string;
}

interface SummaryRow {
  label: string;
  value: string;
  numeric?: boolean;
}

/**
 * Read-only summary of the disbursement parameters surfaced on the action page.
 *
 * Uses `<dl>/<dt>/<dd>` so screen readers can pair labels with values, and
 * applies the tabular-nums attribute to numeric values so figures align.
 */
export function DisbursementSummaryCard({
  approvedPrincipal,
  tenureMonths,
  interestRatePercent,
  scheduledOn,
  firstEmiDate,
  className,
}: DisbursementSummaryCardProps) {
  const rows: SummaryRow[] = [
    { label: "Approved principal", value: formatINR(approvedPrincipal), numeric: true },
    {
      label: "Tenure",
      value: `${tenureMonths} ${tenureMonths === 1 ? "month" : "months"}`,
      numeric: true,
    },
    {
      label: "Interest rate",
      value: `${interestRatePercent.toFixed(2)}% p.a.`,
      numeric: true,
    },
    { label: "Scheduled disbursement", value: formatDate(scheduledOn) },
    { label: "First EMI due", value: formatDate(firstEmiDate) },
  ];

  return (
    <Card className={cn(className)} data-slot="disbursement-summary">
      <CardHeader>
        <CardTitle>Disbursement summary</CardTitle>
      </CardHeader>
      <CardContent>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          {rows.map((row) => (
            <div key={row.label} className="flex flex-col gap-1">
              <dt className="text-foreground-muted text-xs font-medium tracking-wide uppercase">
                {row.label}
              </dt>
              <dd
                className="text-foreground text-sm font-medium"
                {...(row.numeric ? TABULAR_ATTR : {})}
              >
                {row.value}
              </dd>
            </div>
          ))}
        </dl>
      </CardContent>
    </Card>
  );
}
