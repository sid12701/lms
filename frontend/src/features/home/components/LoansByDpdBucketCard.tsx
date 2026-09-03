import { forwardRef, useMemo } from "react";
import { BarChart3 } from "lucide-react";
import { StatBreakdownCardFrame } from "./StatBreakdownCardFrame";
import { delinquencyBucketShortLabel } from "@/lib/delinquency-display";
import { prefersReducedMotion } from "@/lib/prefers-reduced-motion";
import { ChartSkeleton } from "@/components/app/feedback/Skeletons";
import type { DelinquencyBucket } from "@/schemas/loan-account";
import type { DpdBucketSummary } from "../types";
import { DpdBucketChart, type DpdChartDatum } from "./DpdBucketChart";

interface LoansByDpdBucketCardProps {
  buckets: readonly DpdBucketSummary[];
  isLoading?: boolean;
  className?: string;
}

const BUCKET_FILL: Record<DelinquencyBucket, string> = {
  B0: "var(--color-success)",
  B1_30: "var(--color-info)",
  B31_60: "var(--color-warning)",
  B61_90: "color-mix(in srgb, var(--color-warning) 72%, var(--color-danger))",
  B90_PLUS: "var(--color-danger)",
};

/**
 * Vertical bar chart for loans grouped by DPD bucket. Bar fills encode
 * delinquency severity while the indigo accent stays limited to interaction
 * treatment on the tooltip cursor.
 */
export const LoansByDpdBucketCard = forwardRef<HTMLDivElement, LoansByDpdBucketCardProps>(
  function LoansByDpdBucketCard({ buckets, isLoading = false, className }, ref) {
    const reducedMotion = prefersReducedMotion();

    const data: DpdChartDatum[] = useMemo(() => {
      return buckets.map((b) => ({
        bucket: b.bucket,
        label: delinquencyBucketShortLabel(b.bucket),
        count: b.count,
        fill: BUCKET_FILL[b.bucket],
      }));
    }, [buckets]);

    const total = useMemo(() => data.reduce((acc, d) => acc + d.count, 0), [data]);
    const summary =
      total === 0
        ? "No loans in DPD buckets."
        : `${total} ${total === 1 ? "loan" : "loans"} across ${data.length} DPD buckets.`;

    const headingId = "loans-by-dpd-heading";
    const summaryId = "loans-by-dpd-summary";

    if (isLoading) {
      return <ChartSkeleton ref={ref} className={className} />;
    }

    return (
      <StatBreakdownCardFrame
        ref={ref}
        dataSlot="loans-by-dpd"
        headingId={headingId}
        summaryId={summaryId}
        title="Loans by DPD bucket"
        description="Distribution of overdue loans across delinquency buckets."
        summary={summary}
        className={className}
        empty={total === 0}
        emptyIcon={BarChart3}
        emptyTitle="No loans in DPD buckets"
        emptyDescription="When repayment schedules are available, loans will appear here grouped by DPD bucket."
      >
        <DpdBucketChart data={data} reducedMotion={reducedMotion} />
      </StatBreakdownCardFrame>
    );
  },
);
