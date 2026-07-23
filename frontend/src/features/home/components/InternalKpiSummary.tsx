import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { AlertTriangle, Banknote, Clock, Inbox, Wallet, type LucideIcon } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { KpiStrip } from "@/components/app/layout/KpiStrip";
import { formatINR } from "@/lib/format";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import type { InternalHomeKpis } from "../types";

interface InternalKpiSummaryProps {
  kpis: InternalHomeKpis;
  className?: string;
}

interface KpiTileProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  icon: LucideIcon;
  label: string;
  value: ReactNode;
  /** Smaller line under the value (e.g. "₹X.XX cr overdue"). */
  support?: ReactNode;
  /** When the value should be rendered with tabular-nums. */
  numeric?: boolean;
}

/**
 * Single KPI tile used by the internal/LSP KPI summaries. Mirrors the
 * KpiSkeleton card geometry so loading → loaded swaps avoid CLS.
 */
function KpiTile({
  icon: Icon,
  label,
  value,
  support,
  numeric = true,
  className,
  ...rest
}: KpiTileProps) {
  return (
    <Card
      data-slot="kpi-tile"
      className={cn(
        "border-border bg-surface shadow-e1 flex flex-col gap-2 rounded-md border p-5 py-5 shadow-sm",
        className,
      )}
      {...rest}
    >
      <CardContent className="flex flex-col gap-2 px-0">
        <div className="text-foreground-muted flex items-center gap-2 text-[11px] font-medium tracking-[0.08em] uppercase">
          <Icon aria-hidden="true" className="size-3.5" />
          <span>{label}</span>
        </div>
        <div
          data-slot="kpi-value"
          className="text-foreground text-2xl leading-8 font-semibold"
          {...(numeric ? TABULAR_ATTR : {})}
        >
          {value}
        </div>
        {support ? (
          <div
            className="text-foreground-muted text-xs leading-4"
            {...(numeric ? TABULAR_ATTR : {})}
          >
            {support}
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}

/**
 * Five-up KPI strip for the internal home dashboard. Reads aggregate
 * counts/amounts from `InternalHomeKpis` and renders them through the
 * shared `KpiStrip` grid. Values are always derived from the supplied
 * payload — no static decoration.
 */
export const InternalKpiSummary = forwardRef<HTMLDivElement, InternalKpiSummaryProps>(
  function InternalKpiSummary({ kpis, className }, ref) {
    const tatValue =
      kpis.avgApprovalTatHours == null ? "—" : `${kpis.avgApprovalTatHours.toFixed(1)} h`;

    return (
      <KpiStrip
        ref={ref}
        className={cn("xl:grid-cols-5", className)}
        aria-label="Internal home metrics"
      >
        <KpiTile icon={Inbox} label="Awaiting approval" value={kpis.applicationsAwaitingApproval} />
        <KpiTile icon={Wallet} label="In disbursement" value={kpis.applicationsInDisbursement} />
        <KpiTile icon={Banknote} label="MTD disbursed" value={formatINR(kpis.mtdDisbursedAmount)} />
        <KpiTile
          icon={AlertTriangle}
          label="Overdue loans"
          value={kpis.overdueLoansCount}
          support={`${formatINR(kpis.overdueAmount)} outstanding`}
        />
        <KpiTile icon={Clock} label="Avg approval TAT" value={tatValue} numeric />
      </KpiStrip>
    );
  },
);
