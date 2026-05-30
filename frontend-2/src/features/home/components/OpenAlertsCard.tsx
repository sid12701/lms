/* eslint-disable react-refresh/only-export-components -- module co-exports SEVERITY_TOKEN for downstream consumers/tests */
import { forwardRef } from "react";
import { Link } from "react-router-dom";
import { AlertCircle, AlertTriangle, BellOff, Info, type LucideIcon } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { AlertSeverity } from "@/schemas/alert";
import type { HomeAlertSummary } from "../types";

export interface OpenAlertsCardProps {
  alerts: readonly HomeAlertSummary[];
  className?: string;
}

type Token = "danger" | "warning" | "info" | "neutral";

interface SeverityMeta {
  icon: LucideIcon;
  token: Token;
  label: string;
  classes: string;
  iconClass: string;
}

/** Severity → icon + token mapping. Color is never the sole signal. */
const SEVERITY_META: Record<AlertSeverity, SeverityMeta> = {
  CRITICAL: {
    icon: AlertTriangle,
    token: "danger",
    label: "Critical",
    classes: "border-danger/30 bg-danger/10 text-danger",
    iconClass: "text-danger",
  },
  HIGH: {
    icon: AlertCircle,
    token: "warning",
    label: "High",
    classes: "border-warning/30 bg-warning/10 text-warning",
    iconClass: "text-warning",
  },
  MEDIUM: {
    icon: Info,
    token: "info",
    label: "Medium",
    classes: "border-info/30 bg-info/10 text-info",
    iconClass: "text-info",
  },
  LOW: {
    icon: Info,
    token: "neutral",
    label: "Low",
    classes: "border-border bg-surface-muted text-foreground-muted",
    iconClass: "text-foreground-muted",
  },
};

export const SEVERITY_TOKEN: Record<AlertSeverity, Token> = {
  CRITICAL: "danger",
  HIGH: "warning",
  MEDIUM: "info",
  LOW: "neutral",
};

/**
 * Compact feed of open operational alerts. Rows include severity (icon
 * + label), short title, subject (LOAN_APPLICATION rows link through to
 * the detail page), and a relative timestamp.
 */
export const OpenAlertsCard = forwardRef<HTMLDivElement, OpenAlertsCardProps>(
  function OpenAlertsCard({ alerts, className }, ref) {
    return (
      <Card ref={ref} data-slot="open-alerts" className={cn(className)}>
        <CardHeader>
          <CardTitle>Open alerts</CardTitle>
        </CardHeader>
        <CardContent>
          {alerts.length === 0 ? (
            <EmptyState icon={BellOff} title="No open alerts" description="You're all caught up." />
          ) : (
            <ul className="flex flex-col gap-2" data-slot="open-alerts-list">
              {alerts.map((alert) => {
                const meta = SEVERITY_META[alert.severity];
                const Icon = meta.icon;
                return (
                  <li
                    key={alert.id}
                    data-slot="alert-row"
                    data-severity={alert.severity}
                    data-token={meta.token}
                    className={cn("flex items-start gap-3 rounded-md border p-3", meta.classes)}
                  >
                    <Icon
                      aria-hidden="true"
                      className={cn("mt-0.5 size-4 shrink-0", meta.iconClass)}
                    />
                    <div className="flex min-w-0 flex-1 flex-col gap-1">
                      <div className="flex flex-wrap items-baseline gap-x-2">
                        <span className="text-foreground text-sm font-medium">{alert.title}</span>
                        <span
                          className="text-foreground-muted text-xs tracking-wide uppercase"
                          aria-label={`Severity ${meta.label}`}
                        >
                          {meta.label}
                        </span>
                      </div>
                      <div className="text-foreground-muted flex flex-wrap items-center gap-x-2 text-xs">
                        {alert.subjectType === "LOAN_APPLICATION" ? (
                          <Link
                            to={`/loan-applications/${alert.subjectId}`}
                            className="text-info font-mono hover:underline"
                          >
                            {alert.subjectId.slice(0, 8)}
                          </Link>
                        ) : (
                          <span className="font-mono">
                            {alert.subjectType.toLowerCase()} · {alert.subjectId.slice(0, 8)}
                          </span>
                        )}
                        <span aria-hidden="true">·</span>
                        <span>{formatRelative(alert.createdAt)}</span>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>
    );
  },
);
