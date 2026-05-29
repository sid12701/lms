import { forwardRef, useMemo } from "react";
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { STATUS_META, type Intent } from "@/lib/lifecycle";
import { cn } from "@/lib/utils";
import { BarChart3 } from "lucide-react";
import type { ApplicationsByStatusBucket } from "../types";

export interface ApplicationsByStatusCardProps {
  buckets: readonly ApplicationsByStatusBucket[];
  className?: string;
}

/**
 * Map a status's `intent` to the CSS variable name for its design token.
 * Recharts needs a plain colour string, so we resolve through CSS vars
 * declared in the theme layer (e.g. `--color-success`).
 */
const INTENT_FILL: Record<Intent, string> = {
  success: "var(--color-success)",
  warning: "var(--color-warning)",
  danger: "var(--color-danger)",
  info: "var(--color-info)",
  progress: "var(--color-progress)",
  neutral: "var(--color-foreground-muted)",
  revoked: "var(--color-revoked)",
};

interface ChartDatum {
  status: string;
  label: string;
  intent: Intent;
  count: number;
  fill: string;
}

/**
 * Read `prefers-reduced-motion` once at render. The home dashboard is a
 * read-only surface — a single sync read is fine; we don't need a live
 * subscription for an entrance animation toggle.
 */
function prefersReducedMotion(): boolean {
  if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
    return false;
  }
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

/**
 * Horizontal bar chart of "applications by status". Bars are coloured by
 * the lifecycle intent of each status, surfacing at-a-glance health
 * (success vs warning vs danger). Animation is suppressed when the user
 * has opted into reduced motion.
 *
 * jsdom workaround: Recharts' ResponsiveContainer measures the parent
 * width via ResizeObserver. The shared `renderWithProviders` polyfills
 * `ResizeObserver` but reports zero dimensions, so the chart tree may
 * render an empty SVG. Tests assert against the surrounding section's
 * a11y summary plus a `data-testid` we add on the chart wrapper rather
 * than against the SVG geometry itself.
 */
export const ApplicationsByStatusCard = forwardRef<HTMLDivElement, ApplicationsByStatusCardProps>(
  function ApplicationsByStatusCard({ buckets, className }, ref) {
    const reducedMotion = prefersReducedMotion();

    const data: ChartDatum[] = useMemo(() => {
      return buckets.map((b) => {
        const meta = STATUS_META[b.status];
        return {
          status: b.status,
          label: meta?.label ?? b.status,
          intent: meta?.intent ?? "neutral",
          count: b.count,
          fill: INTENT_FILL[meta?.intent ?? "neutral"],
        };
      });
    }, [buckets]);

    const total = useMemo(() => data.reduce((acc, d) => acc + d.count, 0), [data]);
    const summary = `${data.length} ${data.length === 1 ? "status" : "statuses"}, total ${total} ${total === 1 ? "application" : "applications"}`;

    const headingId = "applications-by-status-heading";
    const summaryId = "applications-by-status-summary";

    return (
      <Card
        ref={ref}
        data-slot="applications-by-status"
        className={cn(className)}
        aria-labelledby={headingId}
        aria-describedby={summaryId}
      >
        <CardHeader>
          <CardTitle id={headingId}>Applications by status</CardTitle>
        </CardHeader>
        <CardContent>
          <span id={summaryId} className="sr-only">
            {summary}
          </span>
          {data.length === 0 ? (
            <EmptyState
              icon={BarChart3}
              title="No applications yet"
              description="When applications come in they'll appear here grouped by status."
            />
          ) : (
            <div
              data-testid="applications-by-status-chart"
              data-reduced-motion={reducedMotion || undefined}
              aria-label="Applications by status"
              role="img"
              className="h-[280px] w-full"
            >
              <ResponsiveContainer width="100%" height="100%">
                <BarChart
                  data={[...data]}
                  layout="vertical"
                  margin={{ top: 8, right: 24, bottom: 8, left: 8 }}
                >
                  <XAxis
                    type="number"
                    allowDecimals={false}
                    tick={{ fontSize: 12, fill: "var(--color-foreground-muted)" }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    type="category"
                    dataKey="label"
                    width={160}
                    tick={{ fontSize: 12, fill: "var(--color-foreground)" }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <Tooltip
                    cursor={{ fill: "var(--color-surface-muted)" }}
                    contentStyle={{
                      background: "var(--color-surface)",
                      border: "1px solid var(--color-border)",
                      borderRadius: 6,
                      fontSize: 12,
                    }}
                  />
                  <Bar dataKey="count" isAnimationActive={!reducedMotion} radius={[0, 4, 4, 0]}>
                    {data.map((d) => (
                      <Cell
                        key={d.status}
                        fill={d.fill}
                        data-status={d.status}
                        data-intent={d.intent}
                        data-fill={d.fill}
                      />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </CardContent>
      </Card>
    );
  },
);
