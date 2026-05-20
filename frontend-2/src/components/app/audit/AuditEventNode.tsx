import { forwardRef, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import {
  ArrowRightLeft,
  Eye,
  FileSearch,
  FileText,
  Package,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { formatDateTime, formatRelative } from "@/lib/format";
import type { Role } from "@/types";
import {
  AUDIT_STREAM_LABEL,
  type AuditEvent,
  type AuditStreamKind,
  getAuditCommon,
} from "./types";

export interface AuditEventNodeProps extends HTMLAttributes<HTMLLIElement> {
  entry: AuditEvent;
  /** Compact density per D7 — used on `/audit` and other long-list surfaces. */
  compact?: boolean;
  /** Pin "now" to a fixed Date for deterministic relative-time tests. */
  now?: Date;
  className?: string;
}

const ICON_BY_KIND: Record<AuditStreamKind, LucideIcon> = {
  APPLICATION: ArrowRightLeft,
  INTAKE: FileText,
  PII_REVEAL: Eye,
  DOCUMENT_ACCESS: FileSearch,
  PRODUCT: Package,
};

/** Tone the kind chip; mirrors the lifecycle palette without claiming intent. */
const KIND_CHIP_TONE: Record<AuditStreamKind, string> = {
  APPLICATION: "border-info/30 bg-info/10 text-info",
  INTAKE: "border-progress/30 bg-progress/10 text-progress",
  PII_REVEAL: "border-warning/30 bg-warning/10 text-warning",
  DOCUMENT_ACCESS: "border-border bg-surface-muted text-foreground-muted",
  PRODUCT: "border-success/30 bg-success/10 text-success",
};

const ROLE_LABEL: Record<Role, string> = {
  SYSTEM_ADMIN: "System admin",
  OPS_USER: "Ops",
  PRODUCT_ADMIN: "Product admin",
  LSP_UI_READ: "LSP read",
  LSP_UI_WRITE: "LSP write",
  LSP_API_CLIENT: "LSP API",
};

function formatActor(id: string, role: Role | null): string {
  // Render a deliberately short actor stamp — full id is shown via title
  // attribute for hover/tooltip use. The Audit page's filter bar carries
  // the role legend; we only need a 2-token preview here.
  const head = id.length > 8 ? `${id.slice(0, 8)}…` : id;
  return role ? `${ROLE_LABEL[role]} · ${head}` : head;
}

function summarize(entry: AuditEvent): { headline: string; detail: string | null } {
  switch (entry.kind) {
    case "APPLICATION": {
      const e = entry.event;
      const arrow = `${e.fromStatus ?? "—"} → ${e.toStatus}`;
      return { headline: arrow, detail: e.action };
    }
    case "INTAKE":
      return { headline: "Intake snapshot recorded", detail: null };
    case "PII_REVEAL": {
      const e = entry.event;
      // PII reveals never expose the cleartext value here — the row records
      // the *act* of revealing, not the revealed payload (BR-7).
      return { headline: `Revealed ${e.fieldName}`, detail: e.reason };
    }
    case "DOCUMENT_ACCESS":
      return { headline: `Document ${entry.event.action.toLowerCase()}`, detail: null };
    case "PRODUCT":
      return { headline: `Product ${entry.event.action.toLowerCase().replace(/_/g, " ")}`, detail: null };
  }
}

/**
 * Single timeline entry for any of the five audit streams. Discriminates
 * on `entry.kind`, picks an icon + summary, and renders the shared header
 * row (kind chip · actor · timestamp · correlation id).
 *
 * PII reveal rows surface the long `reason` via a native `<details>`
 * element — no cleartext PII is ever displayed by the audit timeline.
 */
export const AuditEventNode = forwardRef<HTMLLIElement, AuditEventNodeProps>(
  function AuditEventNode({ entry, compact = false, now, className, ...rest }, ref) {
    const common = getAuditCommon(entry);
    const Icon = ICON_BY_KIND[entry.kind];
    const { headline, detail } = summarize(entry);
    const absolute = formatDateTime(common.timestamp);
    const relative = formatRelative(common.timestamp, now);

    return (
      <li
        ref={ref}
        data-slot="audit-event-node"
        data-kind={entry.kind}
        data-compact={compact ? "true" : "false"}
        className={cn(
          "border-border bg-surface relative flex gap-3 border-l-2 pl-4",
          compact ? "py-1.5" : "py-3",
          className,
        )}
        {...rest}
      >
        <span
          aria-hidden="true"
          className={cn(
            "border-border bg-surface absolute -left-2.5 top-3 inline-flex size-5 items-center justify-center rounded-full border",
          )}
        >
          <Icon className={cn("size-3", "text-foreground-muted")} aria-hidden="true" />
        </span>

        <div className="flex flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <Badge
              data-slot="audit-event-kind"
              className={cn("uppercase tracking-wide", KIND_CHIP_TONE[entry.kind])}
            >
              {AUDIT_STREAM_LABEL[entry.kind]}
            </Badge>
            <span
              data-slot="audit-event-headline"
              className={cn(
                "text-foreground font-medium",
                compact ? "text-xs" : "text-sm",
                entry.kind === "APPLICATION" ? "font-mono tabular-nums" : null,
              )}
            >
              {headline}
            </span>
            <span
              data-slot="audit-event-actor"
              className={cn(
                "text-foreground-muted",
                compact ? "text-[11px]" : "text-xs",
              )}
              title={common.actorId}
            >
              {formatActor(common.actorId, common.actorRole)}
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <time
              data-slot="audit-event-timestamp"
              dateTime={common.timestamp}
              className={cn(
                "text-foreground-muted tabular-nums",
                compact ? "text-[11px]" : "text-xs",
              )}
            >
              <span>{absolute}</span>
              <span aria-hidden="true" className="px-1">
                ·
              </span>
              <span data-slot="audit-event-relative">{relative}</span>
            </time>
            <span
              data-slot="audit-event-correlation"
              title={common.correlationId}
              className={cn(
                "text-foreground-muted truncate font-mono",
                compact ? "max-w-[6rem] text-[11px]" : "max-w-[9rem] text-xs",
              )}
              aria-label={`Correlation id ${common.correlationId}`}
            >
              {common.correlationId}
            </span>
          </div>

          {detail ? (
            entry.kind === "PII_REVEAL" && detail.length > 60 ? (
              <details data-slot="audit-event-detail" className="text-foreground-muted text-xs">
                <summary className="cursor-pointer select-none">
                  {detail.slice(0, 60)}…
                </summary>
                <p className="mt-1 leading-relaxed">{detail}</p>
              </details>
            ) : (
              <p
                data-slot="audit-event-detail"
                className={cn("text-foreground-muted", compact ? "text-[11px]" : "text-xs")}
              >
                {detail}
              </p>
            )
          ) : null}
        </div>
      </li>
    );
  },
);
