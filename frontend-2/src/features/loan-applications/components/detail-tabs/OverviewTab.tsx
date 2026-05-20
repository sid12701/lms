import { type ReactNode } from "react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { MaskedField } from "@/components/app/pii/MaskedField";
import { formatDateTime, formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { LoanApplicationDetail } from "../../types";

export interface OverviewTabProps {
  detail: LoanApplicationDetail;
}

/**
 * No-op PII reveal handler — wiring an audit write requires the future
 * `mocks/api/audit.ts` surface (Phase 8). Until then the dialog still
 * displays + records the user's reason locally so the UX path is
 * representative.
 */
function noopReveal(): Promise<void> {
  return Promise.resolve();
}

function Section({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="border-border bg-surface rounded-md border p-4">
      <h2 className="text-foreground mb-3 text-sm font-semibold tracking-tight">
        {title}
      </h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
        {children}
      </dl>
    </section>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-foreground-muted text-xs tracking-wide uppercase">{label}</dt>
      <dd className="text-foreground text-sm tabular-nums">{children}</dd>
    </div>
  );
}

function GateChip({ ok, label, hint }: { ok: boolean; label: string; hint: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Badge
          data-slot="gate-chip"
          data-ok={ok ? "true" : "false"}
          className={cn(
            "cursor-default border",
            ok
              ? "border-success/30 bg-success/10 text-success"
              : "border-warning/30 bg-warning/10 text-warning",
          )}
        >
          {ok ? "✓" : "!"} {label}
        </Badge>
      </TooltipTrigger>
      <TooltipContent>{hint}</TooltipContent>
    </Tooltip>
  );
}

/**
 * Overview tab — read-only summary of the application's loan terms, the
 * borrower's masked PII (BR-7), the originating LSP, the assigned operator,
 * and the live lifecycle-gate state. Every PII field renders through
 * `MaskedField` so the audit-write contract is preserved when the audit
 * mutation lands.
 */
export function OverviewTab({ detail }: OverviewTabProps) {
  const { application, borrower, lsp, product } = detail;
  const assignedLabel = application.assignedTo ? "Assigned operator" : "Assignment";

  return (
    <div
      data-slot="overview-tab"
      className="grid grid-cols-1 gap-4 lg:grid-cols-2"
    >
      <Section title="Loan terms">
        <Row label="Requested amount">{formatINR(application.requestedAmount)}</Row>
        <Row label="Tenure">{application.tenureMonths} months</Row>
        <Row label="Product">{product.name}</Row>
        <Row label="Source channel">{application.sourceChannel}</Row>
        <Row label="Created">{formatDateTime(application.createdAt)}</Row>
        <Row label="Updated">{formatDateTime(application.updatedAt)}</Row>
      </Section>

      <Section title="Borrower">
        <Row label="Full name">{borrower.fullName}</Row>
        <Row label="PAN">
          <MaskedField
            fieldName="PAN"
            value={borrower.pan}
            subjectLabel={borrower.fullName}
            onReveal={noopReveal}
          />
        </Row>
        <Row label="Aadhaar">
          <MaskedField
            fieldName="AADHAAR"
            value={borrower.aadhaar}
            subjectLabel={borrower.fullName}
            onReveal={noopReveal}
          />
        </Row>
        <Row label="Mobile">
          <MaskedField
            fieldName="MOBILE"
            value={borrower.mobile}
            subjectLabel={borrower.fullName}
            onReveal={noopReveal}
          />
        </Row>
      </Section>

      <Section title="Lending service provider">
        <Row label="Name">{lsp.name}</Row>
        <Row label="LSP id">
          <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
            {lsp.id}
          </code>
        </Row>
      </Section>

      <Section title={assignedLabel}>
        <Row label="Operator">
          {application.assignedTo ? (
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {application.assignedTo}
            </code>
          ) : (
            <span className="text-foreground-muted">Unassigned</span>
          )}
        </Row>
      </Section>

      <section
        data-slot="overview-gates"
        className="border-border bg-surface rounded-md border p-4 lg:col-span-2"
      >
        <h2 className="text-foreground mb-3 text-sm font-semibold tracking-tight">
          Lifecycle gates
        </h2>
        <div className="flex flex-wrap items-center gap-2">
          <GateChip
            ok={detail.docsComplete}
            label={detail.docsComplete ? "Docs complete" : "Docs incomplete"}
            hint="BR-3 — every required-for-disbursement document must be VERIFIED."
          />
          <GateChip
            ok={detail.scheduleValid}
            label={detail.scheduleValid ? "Schedule valid" : "Schedule missing"}
            hint="BR-10 — a valid repayment schedule must exist before disbursement."
          />
        </div>
      </section>
    </div>
  );
}

export default OverviewTab;
