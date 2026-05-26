/* eslint-disable react-refresh/only-export-components -- module co-exports the meta lookup helper for tests */
import { forwardRef, type HTMLAttributes } from "react";
import type { LucideIcon } from "lucide-react";
import { Clock, FileUp } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { DocumentStatus } from "@/schemas/document";

export type DocumentStatusPillTone = "warning" | "info";

interface DocumentStatusMeta {
  label: string;
  tone: DocumentStatusPillTone;
  icon: LucideIcon;
}

// Gap #18 — VERIFIED/REJECTED removed. Only PENDING + UPLOADED remain.
const STATUS_META: Record<DocumentStatus, DocumentStatusMeta> = {
  PENDING: { label: "Pending", tone: "warning", icon: Clock },
  UPLOADED: { label: "Uploaded", tone: "info", icon: FileUp },
};

export function resolveDocumentStatusMeta(status: DocumentStatus): DocumentStatusMeta {
  switch (status) {
    case "PENDING":
    case "UPLOADED":
      return STATUS_META[status];
    default: {
      const _exhaustive: never = status;
      return { label: String(_exhaustive), tone: "warning", icon: Clock };
    }
  }
}

const TONE_CLASSES: Record<DocumentStatusPillTone, { wrap: string; icon: string }> = {
  warning: {
    wrap: "border-warning/30 bg-warning/10 text-warning",
    icon: "text-warning",
  },
  info: {
    wrap: "border-info/30 bg-info/10 text-info",
    icon: "text-info",
  },
};

export interface DocumentStatusPillProps extends Omit<HTMLAttributes<HTMLSpanElement>, "children"> {
  status: DocumentStatus;
  hideIcon?: boolean;
  className?: string;
}

export const DocumentStatusPill = forwardRef<HTMLSpanElement, DocumentStatusPillProps>(
  function DocumentStatusPill({ status, hideIcon = false, className, ...rest }, ref) {
    const meta = resolveDocumentStatusMeta(status);
    const classes = TONE_CLASSES[meta.tone];
    const Icon = meta.icon;
    return (
      <Badge
        ref={ref}
        data-slot="document-status-pill"
        data-status={status}
        data-tone={meta.tone}
        className={cn(classes.wrap, className)}
        {...rest}
      >
        {hideIcon ? null : <Icon aria-hidden="true" className={cn("size-3", classes.icon)} />}
        <span>{meta.label}</span>
      </Badge>
    );
  },
);
