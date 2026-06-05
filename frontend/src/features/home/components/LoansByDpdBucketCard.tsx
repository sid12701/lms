import { forwardRef, useMemo } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { BarChart3 } from "lucide-react";
import { StatBreakdownCardFrame } from "./StatBreakdownCardFrame";
import { prefersReducedMotion } from "@/lib/prefers-reduced-motion";
import { ChartSkeleton } from "@/components/app/feedback/Skeletons";
import type { DelinquencyBucket } from "@/schemas/loan-account";
import type { DpdBucketSummary } from "../types";

export interface LoansByDpdBucketCardProps {
  buckets: readonly DpdBucketSummary[];
  isLoading?: boolean;
  className?: string;
}

const BUCKET_LABEL: Record<DelinquencyBucket, string> = {
  B0: "Current",
  B1_30: "0-30",
  B31_60: "30-60",
  B61_90: "60-90",
  B90_PLUS: "90+",
};

const BUCKET_FILL: Record<DelinquencyBucket, string> = {
  B0: "var(--color-success)",
  B1_30: "var(--color-info)",
  B31_60: "var(--color-warning)",
  B61_90: "color-mix(in srgb, var(--color-warning) 72%, var(--color-danger))",
  B90_PLUS: "var(--color-danger)",
};

interface ChartDatum {
  bucket: DelinquencyBucket;
  label: string;
  count: number;
  fill: string;
}

/**
 * Vertical bar chart for loans grouped by DPD bucket. Bar fills encode
 * delinquency severity while the indigo accent stays limited to interaction
 * treatment on the tooltip cursor.
 */
export const LoansByDpdBucketCard = forwardRef<HTMLDivElement, LoansByDpdBucketCardProps>(
  function LoansByDpdBucketCard({ buckets, isLoading = false, className }, ref) {
    const reducedMotion = prefersReducedMotion();

    const data: ChartDatum[] = useMemo(() => {
      return buckets.map((b) => ({
        bucket: b.bucket,
        label: BUCKET_LABEL[b.bucket],
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
        <div
          data-testid="loans-by-dpd-chart"
          data-reduced-motion={reducedMotion || undefined}
          aria-label="Loans by DPD bucket"
          role="img"
          className="h-[280px] w-full opacity-100 transition-opacity duration-200 motion-reduce:transition-none"
        >
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={[...data]} margin={{ top: 8, right: 16, bottom: 8, left: 8 }}>
              <CartesianGrid
                strokeDasharray="3 3"
                stroke="color-mix(in srgb, var(--color-accent) 18%, var(--color-border))"
                vertical={false}
              />
              <XAxis
                dataKey="label"
                tick={{ fontSize: 12, fill: "var(--color-foreground-muted)" }}
                axisLine={{ stroke: "var(--color-accent)" }}
                tickLine={false}
                label={{
                  value: "DPD bucket",
                  position: "insideBottom",
                  offset: -2,
                  fontSize: 11,
                  fill: "var(--color-foreground-subtle)",
                }}
              />
              <YAxis
                allowDecimals={false}
                tick={{ fontSize: 12, fill: "var(--color-foreground-muted)" }}
                axisLine={false}
                tickLine={false}
                width={36}
                label={{
                  value: "Loans",
                  angle: -90,
                  position: "insideLeft",
                  offset: 12,
                  fontSize: 11,
                  fill: "var(--color-foreground-subtle)",
                }}
              />
              <Tooltip
                cursor={{ fill: "color-mix(in srgb, var(--color-accent) 12%, transparent)" }}
                contentStyle={{
                  background: "var(--color-surface)",
                  border: "1px solid var(--color-border)",
                  borderRadius: 6,
                  fontSize: 12,
                }}
                formatter={(value: number) => [
                  `${value} ${value === 1 ? "loan" : "loans"}`,
                  "Count",
                ]}
              />
              <Bar dataKey="count" isAnimationActive={!reducedMotion} radius={[4, 4, 0, 0]}>
                {data.map((d) => (
                  <Cell key={d.bucket} fill={d.fill} data-bucket={d.bucket} data-fill={d.fill} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </StatBreakdownCardFrame>
    );
  },
);
