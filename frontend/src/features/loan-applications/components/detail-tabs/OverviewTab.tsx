import { type ReactNode } from "react";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { Badge } from "@/components/ui/badge";
import { formatDateTime, formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import type { BorrowerDetail } from "@/features/borrowers/types";
import type { LoanApplicationDetail } from "../../types";
import { BlockingIssuesPanel } from "./BlockingIssuesPanel";

export interface OverviewTabProps {
  detail: LoanApplicationDetail;
  /**
   * Optional richer borrower projection fetched in parallel (Gap #20).
   * When present, the borrower section augments the thin projection with
   * visible-LSP context and the activeOverdueAmount/totals KPIs.
   */
  borrowerDetail?: BorrowerDetail | null;
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="border-border bg-surface rounded-md border p-4">
      <h2 className="text-foreground mb-3 text-sm font-semibold tracking-tight">{title}</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">{children}</dl>
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
 * borrower's masked PII, the originating LSP, and the live lifecycle-gate
 * state. PII fields render as-supplied by
 * the backend (which masks identity numbers); there is no reveal path
 * (see `docs/gap-fixes.md` § Gap #1).
 */
export function OverviewTab({ detail, borrowerDetail }: OverviewTabProps) {
  const { application, borrower, lsp, product } = detail;
  // Prefer the fuller borrower projection when it has resolved (Gap #20),
  // otherwise fall back to the loan-app's embedded thin projection. Aadhaar
  // is masked on the backend in both cases (Gap #1).
  const richBorrower = borrowerDetail?.borrower ?? borrower;

  return (
    <div data-slot="overview-tab" className="flex flex-col gap-4">
      <BlockingIssuesPanel detail={detail} borrowerDetail={borrowerDetail} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Section title="Loan terms">
          <Row label="Requested amount">{formatINR(application.requestedAmount)}</Row>
          <Row label="Tenure">{application.tenureMonths} months</Row>
          <Row label="Product">{product.name}</Row>
          <Row label="Source channel">{application.sourceChannel}</Row>
          <Row label="Created">{formatDateTime(application.createdAt)}</Row>
          <Row label="Updated">{formatDateTime(application.updatedAt)}</Row>
        </Section>

        <Section title="Borrower">
          <Row label="Full name">{richBorrower.fullName}</Row>
          <Row label="PAN">
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {richBorrower.pan}
            </code>
          </Row>
          <Row label="Aadhaar">
            <code
              data-slot="aadhaar"
              className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs"
            >
              {richBorrower.aadhaar}
            </code>
          </Row>
          <Row label="Mobile">{richBorrower.mobile}</Row>
          {borrowerDetail ? (
            <>
              <Row label="Active overdue">
                {formatINR(borrowerDetail.totals.activeOverdueAmount)}
              </Row>
              <Row label="Open loans">{borrowerDetail.totals.openApplicationsCount}</Row>
              <Row label="Closed loans">{borrowerDetail.totals.closedApplicationsCount}</Row>
              <Row label="Lifetime disbursed">
                {formatINR(borrowerDetail.totals.lifetimeDisbursedAmount)}
              </Row>
            </>
          ) : null}
        </Section>

        {borrowerDetail && borrowerDetail.visibleLsps.length > 0 ? (
          <Section title="Borrower visibility">
            <Row label="LSPs that can see this borrower">
              <div className="flex flex-wrap gap-1.5">
                {borrowerDetail.visibleLsps.map((entry) => (
                  <Badge
                    key={entry.id}
                    className="border-border bg-surface-muted text-foreground border"
                  >
                    {entry.name}
                  </Badge>
                ))}
              </div>
            </Row>
          </Section>
        ) : null}

        <Section title="Lending service provider">
          <Row label="Name">{lsp.name}</Row>
          <Row label="LSP id">
            <code className="bg-surface-muted rounded px-1.5 py-0.5 font-mono text-xs">
              {lsp.id}
            </code>
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
              hint="BR-3 — every required-for-disbursement document must be uploaded."
            />
            <GateChip
              ok={detail.scheduleValid}
              label={detail.scheduleValid ? "Schedule valid" : "Schedule missing"}
              hint="BR-10 — a valid repayment schedule must exist before disbursement."
            />
          </div>
        </section>
      </div>
    </div>
  );
}
