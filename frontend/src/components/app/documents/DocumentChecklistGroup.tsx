import { useMemo } from "react";
import { FileText, FolderOpen } from "lucide-react";
import { cn } from "@/lib/utils";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import type { Document } from "@/schemas/document";
import { DocumentChecklistRow, type DocumentChecklistRowPermissions } from "./DocumentChecklistRow";
import { DocumentUploadRow } from "./DocumentUploadRow";
import { isDisbursementGatePassed } from "./disbursement-gate";
import type { LoanStatus } from "@/types";

export interface DocumentChecklistGroupProps {
  docs: Document[];
  compact?: boolean;
  permissions?: DocumentChecklistRowPermissions;
  onView?: (doc: Document) => void;
  onDownload?: (doc: Document) => void;
  onUpload?: (doc: Document, args: { idempotencyKey: string }) => Promise<void> | void;
  /**
   * Loan lifecycle status. When the loan is past disbursement the section reads
   * as a record of what was required rather than a gate still to clear.
   */
  loanStatus?: LoanStatus;
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
  loanStatus,
  className,
}: DocumentChecklistGroupProps) {
  const gatePassed = isDisbursementGatePassed(loanStatus);
  const { required, optional } = useMemo(() => {
    const required: Document[] = [];
    const optional: Document[] = [];
    for (const doc of docs) {
      // Group by *effective* gating, not the raw flag. A document type can be
      // required for disbursement in general and still be marked NOT_REQUIRED
      // for this loan — that is what a waiver is. Grouping on the flag alone
      // put such a row under a heading claiming it was required while the row
      // itself said "Not required".
      if (doc.requiredForDisbursement && doc.status !== "NOT_REQUIRED") required.push(doc);
      else optional.push(doc);
    }
    return { required, optional };
  }, [docs]);

  const renderRow = (doc: Document) => {
    // NOT_REQUIRED has no file and never will, so it takes the read-only row
    // rather than an upload affordance for a document nobody needs.
    if (doc.status === "PENDING") {
      return (
        <DocumentUploadRow
          key={doc.id}
          doc={doc}
          compact={compact}
          gatePassed={gatePassed}
          onUpload={onUpload ? (args) => onUpload(doc, args) : undefined}
        />
      );
    }
    return (
      <DocumentChecklistRow
        key={doc.id}
        doc={doc}
        compact={compact}
        gatePassed={gatePassed}
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
        <h2
          id="document-checklist-required-heading"
          className="text-foreground text-sm font-semibold tracking-wide uppercase"
        >
          {gatePassed ? "Required before disbursement" : "Required for disbursement"}
        </h2>
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
        <h2
          id="document-checklist-optional-heading"
          className="text-foreground text-sm font-semibold tracking-wide uppercase"
        >
          Not required for this loan
        </h2>
        {optional.length === 0 ? (
          <EmptyState
            icon={FolderOpen}
            title="No other documents"
            description="Supporting documents, and any requirement waived for this loan, appear here."
          />
        ) : (
          <div className="flex flex-col gap-2">{optional.map(renderRow)}</div>
        )}
      </section>
    </div>
  );
}
