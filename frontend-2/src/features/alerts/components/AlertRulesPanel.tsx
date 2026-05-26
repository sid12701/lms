/**
 * SYSTEM_ADMIN-only view of configured alert rules (Follow-up #2).
 */
import { Shield } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { AlertRuleRow } from "../types";

export interface AlertRulesPanelProps {
  rules: AlertRuleRow[] | undefined;
  isLoading: boolean;
}

export function AlertRulesPanel({ rules, isLoading }: AlertRulesPanelProps) {
  if (isLoading) {
    return (
      <section
        data-slot="alert-rules-panel"
        className="border-border bg-surface-muted/40 rounded-lg border px-4 py-3"
      >
        <p className="text-foreground-muted text-sm">Loading alert rules…</p>
      </section>
    );
  }

  if (!rules?.length) {
    return null;
  }

  return (
    <section
      data-slot="alert-rules-panel"
      className="border-border bg-surface-muted/40 flex flex-col gap-3 rounded-lg border px-4 py-4"
    >
      <div className="flex items-center gap-2">
        <Shield className="text-foreground-muted size-4" aria-hidden />
        <h2 className="text-foreground text-sm font-semibold">Active alert rules</h2>
        <Badge variant="outline" className="text-[10px]">
          {rules.length} rules
        </Badge>
      </div>
      <p className="text-foreground-muted text-xs leading-relaxed">
        Scheduled checks run every few minutes; event-driven rules fire on intake,
        webhooks, and rate limits. Open alerts below were emitted by these rules.
      </p>
      <ul className="grid gap-2 sm:grid-cols-2">
        {rules.map((rule) => (
          <li
            key={rule.id}
            data-testid={`alert-rule-${rule.code}`}
            className="border-border bg-surface rounded-md border px-3 py-2"
          >
            <div className="flex items-start justify-between gap-2">
              <span className="text-foreground text-xs font-medium">{rule.name}</span>
              <Badge
                variant={rule.enabled ? "outline" : "secondary"}
                className="text-[10px] shrink-0"
              >
                {rule.enabled ? "On" : "Off"}
              </Badge>
            </div>
            <p className="text-foreground-muted mt-1 text-[11px] leading-snug">
              {rule.description}
            </p>
            <div className="text-foreground-muted mt-2 flex flex-wrap gap-2 text-[10px] uppercase tracking-wide">
              <span>{rule.audience.replace(/_/g, " ")}</span>
              <span>·</span>
              <span>{rule.triggerKind.toLowerCase()}</span>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
