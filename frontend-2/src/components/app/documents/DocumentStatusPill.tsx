import { forwardRef, type HTMLAttributes } from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { DocumentStatus } from "@/schemas/document";
import { resolveDocumentStatusMeta } from "./documentStatusMeta";

export type DocumentStatusPillTone = "warning" | "info";

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
