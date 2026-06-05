import { forwardRef, type HTMLAttributes, type ReactNode } from "react";
import { AlertTriangle, Banknote, Files, Wallet, type LucideIcon } from "lucide-react";
import { Card, CardContent } from "@/components/ui/card";
import { KpiStrip } from "@/components/app/layout/KpiStrip";
import { formatINR } from "@/lib/format";
import { TABULAR_ATTR } from "@/lib/tabular-nums";
import { cn } from "@/lib/utils";
import type { LspHomeKpis } from "../types";

export interface LspKpiSummaryProps {
  kpis: LspHomeKpis;
  className?: string;
}

interface KpiTileProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  icon: LucideIcon;
  label: string;
  value: ReactNode;
}

function KpiTile({ icon: Icon, label, value, className, ...rest }: KpiTileProps) {
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
          {...TABULAR_ATTR}
        >
          {value}
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Four-up KPI strip for the LSP home dashboard. Scoped to the caller's
 * tenant — never shows cross-LSP totals.
 */
export const LspKpiSummary = forwardRef<HTMLDivElement, LspKpiSummaryProps>(function LspKpiSummary(
  { kpis, className },
  ref,
) {
  return (
    <KpiStrip ref={ref} className={cn(className)} aria-label="LSP home metrics">
      <KpiTile icon={Files} label="My active applications" value={kpis.myActiveApplications} />
      <KpiTile icon={Wallet} label="In disbursement" value={kpis.myInDisbursement} />
      <KpiTile icon={Banknote} label="MTD disbursed" value={formatINR(kpis.myMtdDisbursedAmount)} />
      <KpiTile icon={AlertTriangle} label="My overdue loans" value={kpis.myOverdueLoansCount} />
    </KpiStrip>
  );
});
