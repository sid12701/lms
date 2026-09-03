import { type ReactNode } from "react";
import { AlertTriangle, CheckCircle2 } from "lucide-react";
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
    <section className="border-border bg-surface rounded-container border p-4">
      <h2 className="text-foreground mb-3 text-sm font-semibold tracking-tight">{title}</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">{children}</dl>
    </section>
  );
}

/**
 * A `<dt>`/`<dd>` pair that is never allowed to render an empty `<dd>`.
 *
 * When the backend omitted a masked identity number, the `<dd>` rendered blank.
 * A screen reader then read the term list as "AADHAAR, MOBILE, 9001000540" —
 * silently attaching the *mobile* value to the Aadhaar label, on a KYC record.
 * An absent value has to say that it is absent.
 */
function Row({ label, children }: { label: string; children: ReactNode }) {
  const isEmpty =
    children === null ||
    children === undefined ||
    children === false ||
    (typeof children === "string" && children.trim() === "");
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-foreground-muted text-eyebrow uppercase">{label}</dt>
      <dd className="text-foreground text-sm tabular-nums">
        {isEmpty ? (
          <span className="text-foreground-muted">
            <span aria-hidden="true">—</span>
            <span className="sr-only">Not provided</span>
          </span>
        ) : (
          children
        )}
      </dd>
    </div>
  );
}

/**
 * A lifecycle gate, as colour + icon + text.
 *
 * The Never Colour Alone Rule is an invariant, not a preference, and these
 * chips bypassed `StatusBadge` — so they carried a green/amber fill and a
 * literal `"✓"` / `"!"` character in the text run. That glyph is content, not
 * an icon: a screen reader reads it aloud ("check mark Docs complete"), and it
 * is not the icon channel WCAG 1.4.1 asks for. Real icons, marked
 * `aria-hidden`, restore the third channel without polluting the accessible
 * name.
 */
function GateChip({ ok, label, hint }: { ok: boolean; label: string; hint: string }) {
  const Icon = ok ? CheckCircle2 : AlertTriangle;
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
          <Icon aria-hidden="true" className="size-3" />
          {label}
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
          <Row label="Interest rate">
            {detail.interestRate != null ? `${detail.interestRate}%` : null}
          </Row>
          <Row label="Tenure">{application.tenureMonths} months</Row>
          <Row label="Product">{product.name}</Row>
          <Row label="Source channel">{application.sourceChannel}</Row>
          <Row label="Created">{formatDateTime(application.createdAt)}</Row>
          <Row label="Updated">{formatDateTime(application.updatedAt)}</Row>
        </Section>

        <Section title="Borrower">
          <Row label="Full name">{richBorrower.fullName}</Row>
          {/* The `<code>` wrapper is rendered only when there is something to
              put in it — otherwise `Row` sees a non-empty child and cannot
              apply its "Not provided" fallback. */}
          <Row label="PAN">
            {richBorrower.pan ? (
              <code className="bg-surface-muted rounded-control px-1.5 py-0.5 font-mono text-xs">
                {richBorrower.pan}
              </code>
            ) : null}
          </Row>
          <Row label="Aadhaar">
            {richBorrower.aadhaar ? (
              <code
                data-slot="aadhaar"
                className="bg-surface-muted rounded-control px-1.5 py-0.5 font-mono text-xs"
              >
                {richBorrower.aadhaar}
              </code>
            ) : null}
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
          className="border-border bg-surface rounded-container border p-4 lg:col-span-2"
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
