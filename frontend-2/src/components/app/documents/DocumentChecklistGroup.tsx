import { useMemo } from "react";
import { FileText, FolderOpen } from "lucide-react";
import { cn } from "@/lib/utils";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import type { Document } from "@/schemas/document";
import {
  DocumentChecklistRow,
  type DocumentChecklistRowPermissions,
} from "./DocumentChecklistRow";
import { DocumentUploadRow } from "./DocumentUploadRow";

export interface DocumentChecklistGroupProps {
  docs: Document[];
  compact?: boolean;
  permissions?: DocumentChecklistRowPermissions;
  onView?: (doc: Document) => void;
  onDownload?: (doc: Document) => void;
  onUpload?: (
    doc: Document,
    args: { idempotencyKey: string },
  ) => Promise<void> | void;
  className?: string;
}

// Gap #18 — verify/reject affordances are gone. The group composes a
// "Required for disbursement" section over an "Optional" section; each row
// is either the upload-affordance row (PENDING) or the read-only checklist
// row (UPLOADED).
export function DocumentChecklistGroup({
  docs,
  compact = false,
  permissions,
  onView,
  onDownload,
  onUpload,
  className,
}: DocumentChecklistGroupProps) {
  const { required, optional } = useMemo(() => {
    const required: Document[] = [];
    const optional: Document[] = [];
    for (const doc of docs) {
      if (doc.requiredForDisbursement) required.push(doc);
      else optional.push(doc);
    }
    return { required, optional };
  }, [docs]);

  const renderRow = (doc: Document) => {
    if (doc.status === "PENDING") {
      return (
        <DocumentUploadRow
          key={doc.id}
          doc={doc}
          compact={compact}
          onUpload={onUpload ? (args) => onUpload(doc, args) : undefined}
        />
      );
    }
    return (
      <DocumentChecklistRow
        key={doc.id}
        doc={doc}
        compact={compact}
        permissions={permissions}
        onView={onView ? () => onView(doc) : undefined}
        onDownload={onDownload ? () => onDownload(doc) : undefined}
      />
    );
  };

  return (
    <div
      data-slot="document-checklist-group"
      data-compact={compact ? "true" : "false"}
      className={cn("flex flex-col gap-6", className)}
    >
      <section
        data-slot="document-checklist-required"
        aria-labelledby="document-checklist-required-heading"
        className="flex flex-col gap-3"
      >
        <h3
          id="document-checklist-required-heading"
          className="text-foreground text-sm font-semibold tracking-wide uppercase"
        >
          Required for disbursement
        </h3>
        {required.length === 0 ? (
          <EmptyState
            icon={FileText}
            title="No required documents"
            description="No disbursement-gating documents are configured for this application."
          />
        ) : (
          <div className="flex flex-col gap-2">{required.map(renderRow)}</div>
        )}
      </section>

      <section
        data-slot="document-checklist-optional"
        aria-labelledby="document-checklist-optional-heading"
        className="flex flex-col gap-3"
      >
        <h3
          id="document-checklist-optional-heading"
          className="text-foreground text-sm font-semibold tracking-wide uppercase"
        >
          Optional
        </h3>
        {optional.length === 0 ? (
          <EmptyState
            icon={FolderOpen}
            title="No optional documents"
            description="Optional supporting documents will appear here once attached."
          />
        ) : (
          <div className="flex flex-col gap-2">{optional.map(renderRow)}</div>
        )}
      </section>
    </div>
  );
}
