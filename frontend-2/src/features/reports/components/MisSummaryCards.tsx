/**
 * Five KPI summary cards for the Portfolio MIS surface.
 *
 *  1. Total disbursed MTD (INR)
 *  2. Active loans
 *  3. Total loans
 *  4. Weighted-average yield (%)
 *  5. Portfolio at risk 30+ (%)
 *
 * Renders skeleton placeholders while loading; a single error banner spans
 * the row on failure rather than five duplicated banners.
 */
import type { ReactNode } from "react";
import { Activity, AlertTriangle, Layers, Percent, TrendingUp } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { MisSummary } from "../types";

export interface MisSummaryCardsProps {
  data: MisSummary | undefined;
  isLoading: boolean;
  isError?: boolean;
  className?: string;
}

interface KpiSpec {
  key: string;
  label: string;
  icon: LucideIcon;
  format: (data: MisSummary) => string;
  iconTone: string;
}

const KPI_SPECS: readonly KpiSpec[] = [
  {
    key: "disbursed-mtd",
    label: "Total disbursed (MTD)",
    icon: TrendingUp,
    iconTone: "text-success",
    format: (d) => formatINR(d.totalDisbursedMtd, { compact: true }),
  },
  {
    key: "active-loans",
    label: "Active loans",
    icon: Activity,
    iconTone: "text-info",
    format: (d) => d.activeLoanCount.toLocaleString("en-IN"),
  },
  {
    key: "total-loans",
    label: "Total loans",
    icon: Layers,
    iconTone: "text-foreground-muted",
    format: (d) => d.totalLoanCount.toLocaleString("en-IN"),
  },
  {
    key: "weighted-yield",
    label: "Weighted-avg yield",
    icon: Percent,
    iconTone: "text-accent",
    format: (d) => `${d.weightedAvgYieldPct.toFixed(2)}%`,
  },
  {
    key: "par-30",
    label: "Portfolio at risk 30+",
    icon: AlertTriangle,
    iconTone: "text-warning",
    format: (d) => `${d.portfolioAtRisk30Pct.toFixed(2)}%`,
  },
];

function KpiCard({
  label,
  icon: Icon,
  iconTone,
  value,
  testId,
}: {
  label: string;
  icon: LucideIcon;
  iconTone: string;
  value: ReactNode;
  testId: string;
}) {
  return (
    <Card data-slot="mis-kpi-card" data-testid={testId} className="gap-2 py-4">
      <CardContent className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <p className="text-foreground-muted text-[11px] font-medium tracking-wide uppercase">
            {label}
          </p>
          <p className="text-foreground text-xl font-semibold tabular-nums">{value}</p>
        </div>
        <span
          aria-hidden="true"
          className={cn(
            "border-border bg-surface-muted/60 inline-flex size-9 shrink-0 items-center justify-center rounded-md border",
            iconTone,
          )}
        >
          <Icon className="size-4" />
        </span>
      </CardContent>
    </Card>
  );
}

export function MisSummaryCards({
  data,
  isLoading,
  isError = false,
  className,
}: MisSummaryCardsProps) {
  if (isError) {
    return (
      <div
        role="alert"
        data-slot="mis-kpi-error"
        className={cn(
          "border-danger/30 bg-danger/5 text-danger rounded-md border p-3 text-sm",
          className,
        )}
      >
        Couldn't load portfolio KPIs. Try again in a moment.
      </div>
    );
  }

  if (isLoading || !data) {
    return (
      <div
        data-slot="mis-kpi-row"
        className={cn("grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5", className)}
      >
        {KPI_SPECS.map((spec) => (
          <Card key={spec.key} className="gap-2 py-4">
            <CardContent>
              <Skeleton className="mb-2 h-3 w-24" />
              <Skeleton className="h-6 w-20" />
            </CardContent>
          </Card>
        ))}
      </div>
    );
  }

  return (
    <div
      data-slot="mis-kpi-row"
      className={cn("grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5", className)}
    >
      {KPI_SPECS.map((spec) => (
        <KpiCard
          key={spec.key}
          label={spec.label}
          icon={spec.icon}
          iconTone={spec.iconTone}
          value={spec.format(data)}
          testId={`mis-kpi-${spec.key}`}
        />
      ))}
    </div>
  );
}
