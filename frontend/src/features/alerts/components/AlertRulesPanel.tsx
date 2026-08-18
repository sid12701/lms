/**
 * SYSTEM_ADMIN-only view of configured alert rules (Follow-up #2).
 *
 * Two deliberate structural choices:
 *
 * 1. **Collapsed by default.** This is reference material that changes almost
 *    never, and it previously filled the entire first viewport of a page whose
 *    job is triaging an open-alert queue. The queue now leads; the rules are one
 *    click away with the count visible in the summary.
 * 2. **Rows, not cards.** The rules were bordered cards inside a bordered
 *    panel — two elevation levels to express one flat list. Siblings in a list
 *    are grouped by proximity and separated by a divider; a border per item adds
 *    edges to resolve while scanning and implies an independence they lack.
 */
import { ChevronRight, CircleCheck, CircleSlash, Shield } from "lucide-react";
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
        className="border-border bg-surface-muted/40 rounded-container border px-4 py-3"
      >
        <p className="text-foreground-muted text-sm">Loading alert rules…</p>
      </section>
    );
  }

  if (!rules?.length) {
    return null;
  }

  return (
    <details
      data-slot="alert-rules-panel"
      className="border-border bg-surface-muted/40 group rounded-container border"
    >
      <summary className="focus-visible:ring-ring rounded-container flex cursor-pointer list-none items-center gap-2 px-4 py-3 outline-none focus-visible:ring-2 [&::-webkit-details-marker]:hidden">
        <ChevronRight
          aria-hidden="true"
          className="text-foreground-muted size-4 shrink-0 transition-transform group-open:rotate-90 motion-reduce:transition-none"
        />
        <Shield className="text-foreground-muted size-4 shrink-0" aria-hidden="true" />
        <h2 className="text-foreground text-sm font-semibold">Active alert rules</h2>
        <span className="text-foreground-muted text-xs">({rules.length})</span>
      </summary>

      <div className="px-4 pb-4">
        <p className="text-foreground-muted mb-3 text-xs leading-relaxed">
          Scheduled checks run every few minutes; event-driven rules fire on intake and rate
          limits. Open alerts below were emitted by these rules.
        </p>
        <ul className="divide-border border-border divide-y border-t">
          {rules.map((rule) => {
            const StateIcon = rule.enabled ? CircleCheck : CircleSlash;
            return (
              <li
                key={rule.id}
                data-testid={`alert-rule-${rule.code}`}
                className="flex items-start gap-3 py-2.5"
              >
                <StateIcon
                  aria-hidden="true"
                  className={
                    rule.enabled
                      ? "text-success mt-0.5 size-3.5 shrink-0"
                      : "text-foreground-muted mt-0.5 size-3.5 shrink-0"
                  }
                />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className="text-foreground text-xs font-medium">{rule.name}</span>
                    <span className="text-foreground-muted text-xs">
                      {rule.enabled ? "Enabled" : "Disabled"}
                    </span>
                  </div>
                  <p className="text-foreground-muted mt-0.5 text-xs leading-snug">
                    {rule.description}
                  </p>
                  <div className="text-foreground-muted text-eyebrow mt-1 flex flex-wrap gap-2 uppercase">
                    <span>{rule.audience.replace(/_/g, " ")}</span>
                    <span aria-hidden="true">·</span>
                    <span>{rule.triggerKind.toLowerCase()}</span>
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </details>
  );
}
