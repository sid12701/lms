import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { AlertTriangle, Banknote, Inbox, Wallet, type LucideIcon } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { KpiStrip } from "@/components/app/layout/KpiStrip";
import { MetricHint } from "@/components/app/misc/MetricHint";
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
  /** How the figure is derived; omit where the label is self-explanatory. */
  hint?: ReactNode;
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
  hint,
  numeric = true,
  className,
  ...rest
}: KpiTileProps) {
  return (
    <Card
      data-slot="kpi-tile"
      className={cn(
        // One separation device, not four. This stacked a border, `Card`'s own
        // `ring-1`, the navy `--shadow-e1`, *and* a neutral Tailwind
        // `shadow-sm` from outside the system. A KPI tile is an independent
        // object in a dashboard grid, so it is a Card: card radius, ring, no
        // shadow (DESIGN.md, The Hairline Rule).
        "rounded-card flex flex-col gap-2 p-5",
        className,
      )}
      {...rest}
    >
      <CardContent className="flex flex-col gap-2 px-0">
        <div className="text-foreground-muted text-eyebrow flex items-center gap-2 uppercase">
          <Icon aria-hidden="true" className="size-3.5" />
          <span>{label}</span>
          {hint ? (
            <MetricHint label={label} className="-my-1 -ml-1">
              {hint}
            </MetricHint>
          ) : null}
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
function isRenderableInstant(iso: string | null): iso is string {
  return iso !== null && !Number.isNaN(new Date(iso).getTime());
}

export const InternalKpiSummary = forwardRef<HTMLDivElement, InternalKpiSummaryProps>(
  function InternalKpiSummary({ kpis, className }, ref) {
    /*
     * These figures come from a precomputed portfolio snapshot, not a live
     * query. Without an "as of" line a stale or never-computed snapshot renders
     * an identical, confident `₹0` — and an operator cannot tell "nothing
     * disbursed" from "nobody has computed this". For a supervisory dashboard
     * that is the worst possible failure, so the provenance is stated.
     */
    const asOf = kpis.dataAsOf;
    const uncomputed = kpis.dataAsOf === null;

    return (
      <div className="flex flex-col gap-2">
        {/*
          Four tiles on `KpiStrip`'s default four-up grid, so the row stays a
          single line from xl up. This matters beyond tidiness: a fifth tile
          squeezed each one to ~142px at xl, while a full-notation crore figure
          (₹12,30,00,000) needs ~168px — and the tile clips its overflow, so the
          amount silently lost its last digits at 1280 and 1366.
        */}
        <KpiStrip ref={ref} className={className} aria-label="Internal home metrics">
          <KpiTile
            icon={Inbox}
            label="Awaiting approval"
            value={uncomputed ? "—" : kpis.applicationsAwaitingApproval}
          />
          <KpiTile
            icon={Wallet}
            label="In disbursement"
            value={uncomputed ? "—" : kpis.applicationsInDisbursement}
          />
          <KpiTile
            icon={Banknote}
            label="Total disbursed"
            value={uncomputed ? "—" : formatINR(kpis.totalDisbursedAmount)}
            hint="Every rupee disbursed across the book since inception — not a month-to-date figure."
          />
          <KpiTile
            icon={AlertTriangle}
            label="Overdue loans"
            value={uncomputed ? "—" : kpis.overdueLoansCount}
            support={uncomputed ? undefined : `${formatINR(kpis.overdueAmount)} outstanding`}
            hint="Loans with at least one installment past its due date. The supporting figure is their unpaid balance."
          />
        </KpiStrip>
        <p data-slot="kpi-as-of" className="text-foreground-muted text-xs">
          {uncomputed || !isRenderableInstant(asOf) ? (
            "Portfolio snapshot not yet computed — these figures are unavailable, not zero."
          ) : (
            <>
              Portfolio snapshot as of{" "}
              <AbsoluteRelativeTime iso={asOf} variant="relative" compact />.
            </>
          )}
        </p>
      </div>
    );
  },
);
