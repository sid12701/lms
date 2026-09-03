import { lazy, Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import type { DelinquencyBucket } from "@/schemas/loan-account";

export interface DpdChartDatum {
  bucket: DelinquencyBucket;
  label: string;
  count: number;
  fill: string;
}

interface DpdBucketChartProps {
  data: readonly DpdChartDatum[];
  reducedMotion: boolean;
}

const RechartsDpdBucketChart = lazy(async () => {
  const { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } =
    await import("recharts");

  function LoadedDpdBucketChart({ data, reducedMotion }: DpdBucketChartProps) {
    return (
      <div
        data-testid="loans-by-dpd-chart"
        data-reduced-motion={reducedMotion || undefined}
        aria-label="Loans by DPD bucket"
        role="img"
        className="h-[280px] w-full opacity-100 transition-opacity duration-200 motion-reduce:transition-none"
      >
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={[...data]} margin={{ top: 8, right: 16, bottom: 8, left: 20 }}>
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
              width={44}
              label={{
                value: "Loans",
                angle: -90,
                position: "insideLeft",
                offset: 4,
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
              formatter={(value: number) => [`${value} ${value === 1 ? "loan" : "loans"}`, "Count"]}
            />
            <Bar dataKey="count" isAnimationActive={!reducedMotion} radius={[4, 4, 0, 0]}>
              {data.map((datum) => (
                <Cell
                  key={datum.bucket}
                  fill={datum.fill}
                  data-bucket={datum.bucket}
                  data-fill={datum.fill}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
    );
  }

  return { default: LoadedDpdBucketChart };
});

export function DpdBucketChart(props: DpdBucketChartProps) {
  return (
    <Suspense
      fallback={
        <div role="status" aria-label="Loading loans by DPD chart" className="h-[280px] w-full">
          <Skeleton className="h-full w-full" />
        </div>
      }
    >
      <RechartsDpdBucketChart {...props} />
    </Suspense>
  );
}
