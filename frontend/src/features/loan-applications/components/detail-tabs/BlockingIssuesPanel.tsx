import { type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { formatDate, formatDateTime, formatINR } from "@/lib/format";
import type { BorrowerDetail } from "@/features/borrowers/types";
import type { LoanApplicationDetail } from "../../types";

/**
 * Gap #11 — Blocking Issues diagnostic panel.
 *
 * Separates lifecycle phase (the status enum) from diagnostic data (the
 * "why" behind the current status). Each status surfaces a different
 * data source per the design table; statuses without a blocking issue
 * render an empty panel (return null) so callers can place the component
 * unconditionally without spacing artefacts.
 */
export interface BlockingIssuesPanelProps {
  detail: LoanApplicationDetail;
  borrowerDetail?: BorrowerDetail | null;
}

type PanelTone = "info" | "warning" | "danger" | "success" | "neutral";

const PANEL_TONE_CLASSES: Record<PanelTone, string> = {
  info: "border-info/30 bg-info/5",
  warning: "border-warning/30 bg-warning/5",
  danger: "border-danger/30 bg-danger/5",
  success: "border-success/30 bg-success/5",
  neutral: "border-border bg-surface",
};

export function BlockingIssuesPanel({ detail, borrowerDetail }: BlockingIssuesPanelProps) {
  const { application } = detail;
  const status = application.status;

  if (status === "INITIALIZED") {
    return <DocsPendingCard detail={detail} />;
  }
  if (status === "APPROVED_PENDING_DISBURSAL") {
    return <PendingDisbursementCard />;
  }
  if (status === "DISBURSEMENT_RETRY") {
    return <DisbursementRetryCard detail={detail} />;
  }
  if (status === "DISBURSED") {
    return <ScheduleStartCard detail={detail} />;
  }
  if (status === "UNDER_REPAYMENT") {
    return <DelinquencyCard borrowerDetail={borrowerDetail} />;
  }
  if (status === "REJECTED") {
    return <RejectedCard detail={detail} />;
  }
  if (status === "INVALID") {
    return <InvalidatedCard detail={detail} />;
  }
  return null;
}

function PanelShell({
  tone,
  title,
  hint,
  children,
}: {
  tone: PanelTone;
  title: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <section
      data-slot="blocking-issues-panel"
      className={`rounded-container border p-4 ${PANEL_TONE_CLASSES[tone]}`}
    >
      <header className="mb-2 flex items-center justify-between">
        <h2 className="text-foreground text-sm font-semibold tracking-tight">{title}</h2>
        {hint ? <span className="text-foreground-muted text-xs">{hint}</span> : null}
      </header>
      <div className="text-foreground text-sm">{children}</div>
    </section>
  );
}

function DocsPendingCard({ detail }: { detail: LoanApplicationDetail }) {
  if (detail.docsComplete) {
    return null;
  }
  return (
    <PanelShell tone="info" title="Blocking issues" hint="Application is awaiting auto-approval">
      Required documents are not yet uploaded. The auto-approval rule engine will re-evaluate the
      application as soon as the last required document is attached.
    </PanelShell>
  );
}

function PendingDisbursementCard() {
  return (
    <PanelShell tone="info" title="Awaiting disbursement">
      The loan has been auto-approved and is queued for disbursement. The payout adapter will pick
      it up on the next scheduler tick.
    </PanelShell>
  );
}

function DisbursementRetryCard({ detail }: { detail: LoanApplicationDetail }) {
  return (
    <PanelShell tone="warning" title="Disbursement retry pending" hint="Last attempt failed">
      Disbursement for loan {detail.application.externalLoanId ?? detail.application.id}
      will be retried automatically. Inspect the Disbursements tab for the detailed adapter
      response.
    </PanelShell>
  );
}

function ScheduleStartCard({ detail }: { detail: LoanApplicationDetail }) {
  const approvedAt = detail.account?.approvedAt ?? null;
  return (
    <PanelShell tone="success" title="Loan disbursed">
      Disbursement completed
      {approvedAt ? ` on ${formatDate(approvedAt)}` : ""}. The first installment will move the loan
      into <Badge variant="outline">UNDER_REPAYMENT</Badge>.
    </PanelShell>
  );
}

function DelinquencyCard({ borrowerDetail }: { borrowerDetail?: BorrowerDetail | null }) {
  const overdue = borrowerDetail?.totals.activeOverdueAmount ?? 0;

  if (overdue <= 0) {
    return (
      <PanelShell tone="success" title="On track">
        Repayments are on schedule.
      </PanelShell>
    );
  }

  return (
    <PanelShell tone="danger" title="Loan is delinquent">
      <ul className="space-y-1">
        <li>
          Active overdue amount: <span className="font-mono">{formatINR(overdue)}</span>
        </li>
        <li className="text-foreground-muted text-xs">
          Per-installment DPD / bucket detail is available on the Repayments tab.
        </li>
      </ul>
    </PanelShell>
  );
}

function RejectedCard({ detail }: { detail: LoanApplicationDetail }) {
  return (
    <PanelShell tone="danger" title="Application rejected">
      The auto-approval rule engine rejected this application. Inspect the Activity tab for the
      failed-rule list captured on the REJECTED transition (
      {detail.application.externalLoanId ?? detail.application.id}).
    </PanelShell>
  );
}

function InvalidatedCard({ detail }: { detail: LoanApplicationDetail }) {
  const reason = detail.application.invalidReason ?? null;
  const at = detail.application.invalidatedAt ?? null;
  return (
    <PanelShell
      tone="warning"
      title="Application invalidated"
      hint={at ? formatDateTime(at) : undefined}
    >
      {reason ? (
        <>
          Reason from LSP: <em>{reason}</em>
        </>
      ) : (
        "Marked invalid by the LSP."
      )}
    </PanelShell>
  );
}
