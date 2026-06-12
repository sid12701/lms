import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { AlertTriangle, ArrowLeft, ChevronRight, Loader2 } from "lucide-react";

import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { Button } from "@/components/ui/button";
import { formatDateTime, formatINR } from "@/lib/format";
import { fetchInvalidReasons, fetchMyLoanDetail, type InvalidReasonOption, type MyLoanDetail } from "./api";
import type { LoanStatus } from "@/types";
import { DocumentsSection } from "./components/DocumentsSection";
import { DetailField } from "./components/DetailField";
import { MarkInvalidDialog } from "./components/MarkInvalidDialog";
import { MaskedBorrowerCard } from "./components/MaskedBorrowerCard";
import { safeApiMessage } from "./utils";

const TERMINAL_STATUSES = new Set<LoanStatus>([
  "INVALID",
  "REJECTED",
  "CLOSED",
  "FORECLOSED",
]);

export function MyLoanDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail, setDetail] = useState<MyLoanDetail | null>(null);
  const [reasons, setReasons] = useState<readonly InvalidReasonOption[]>([]);
  const [loadingReasons, setLoadingReasons] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [markInvalidOpen, setMarkInvalidOpen] = useState(false);

  const [prevId, setPrevId] = useState(id);
  if (id !== prevId) {
    setPrevId(id);
    setLoading(true);
    setError(null);
  }

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    void fetchMyLoanDetail(id)
      .then((payload) => {
        if (!cancelled) setDetail(payload);
      })
      .catch((err) => {
        if (cancelled) return;
        setError(safeApiMessage(err, "Failed to load loan."));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const openMarkInvalid = useCallback(() => {
    setMarkInvalidOpen(true);
    if (reasons.length === 0 && !loadingReasons) {
      setLoadingReasons(true);
      void fetchInvalidReasons()
        .then((rows) => setReasons(rows))
        .catch(() => {
          // Surface the failure inside the dialog (it shows an empty list with
          // a disabled trigger) — non-fatal for the parent page.
        })
        .finally(() => setLoadingReasons(false));
    }
  }, [reasons.length, loadingReasons]);

  if (!id) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <EmptyState
          icon={AlertTriangle}
          title="Missing loan id"
          description="The URL did not include a loan identifier."
        />
      </div>
    );
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <div role="status" className="text-foreground-muted flex items-center gap-2 text-sm">
          <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
          Loading loan details…
        </div>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-4 py-3 text-sm"
        >
          {error ?? "Loan not found."}
        </div>
      </div>
    );
  }

  const isTerminal = TERMINAL_STATUSES.has(detail.status);
  const eyebrow = detail.externalLoanId
    ? `LSP workspace · ${detail.externalLoanId}`
    : "LSP workspace";

  return (
    <div className="flex flex-col gap-6 p-6">
      <nav aria-label="Breadcrumb" className="text-foreground-muted text-xs">
        <Link to="/my-loans" className="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5" />
          <span>My loans</span>
        </Link>
        <ChevronRight aria-hidden="true" className="mx-1 inline h-3.5 w-3.5 align-text-bottom" />
        <span className="text-foreground">{detail.borrowerFullName}</span>
      </nav>

      <PageHeader
        eyebrow={eyebrow}
        title={detail.borrowerFullName}
        description={`${detail.productName} · ${detail.lspName}`}
        actions={
          <div className="flex items-center gap-2">
            <StatusBadge
              status={detail.status}
              delinquency={detail.loanAccount?.delinquency ?? null}
            />
            {!isTerminal ? (
              <Button type="button" variant="outline" size="sm" onClick={openMarkInvalid}>
                <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                <span>Mark invalid</span>
              </Button>
            ) : null}
          </div>
        }
      />

      <section
        data-slot="loan-terms-card"
        className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
      >
        <h2 className="text-base font-semibold">Loan terms</h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
          <DetailField label="Requested amount" value={formatINR(detail.requestedAmount)} mono />
          <DetailField
            label="Interest rate"
            value={detail.interestRate != null ? `${detail.interestRate}%` : null}
            mono
          />
          <DetailField
            label="Tenure"
            value={detail.tenureMonths ? `${detail.tenureMonths} months` : null}
          />
          <DetailField label="Product" value={detail.productName} />
          <DetailField label="Product code" value={detail.productCode} mono />
          <DetailField label="LSP" value={detail.lspName} />
          <DetailField
            label="Created"
            value={detail.createdAt ? formatDateTime(detail.createdAt) : null}
          />
          <DetailField
            label="Updated"
            value={detail.updatedAt ? formatDateTime(detail.updatedAt) : null}
          />
          {detail.invalidatedAt ? (
            <DetailField label="Invalidated at" value={formatDateTime(detail.invalidatedAt)} />
          ) : null}
        </dl>
        {detail.invalidReasonCode ? (
          <div className="border-warning/30 bg-warning/5 text-warning rounded-md border px-3 py-2 text-xs">
            <span className="font-semibold">Invalid reason:</span>{" "}
            <span className="font-mono">{detail.invalidReasonCode}</span>
            {detail.invalidReasonText ? <> — {detail.invalidReasonText}</> : null}
          </div>
        ) : null}
      </section>

      <section
        data-slot="borrower-contact-card"
        className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
      >
        <h2 className="text-base font-semibold">Borrower contact</h2>
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
          <DetailField label="Mobile" value={detail.borrowerMobile} mono />
          <DetailField label="Email" value={detail.borrowerEmail} />
          <DetailField label="Date of birth" value={detail.borrowerDob} mono />
          <DetailField
            label="Location"
            value={[detail.borrowerCity, detail.borrowerState].filter(Boolean).join(", ") || null}
          />
        </dl>
      </section>

      <MaskedBorrowerCard detail={detail} />

      <DocumentsSection applicationId={id} disabled={isTerminal} />

      {detail.loanAccount ? (
        <section
          data-slot="loan-account-card"
          className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
        >
          <h2 className="text-base font-semibold">Loan account</h2>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
            <DetailField label="Account number" value={detail.loanAccount.accountNumber} mono />
            <DetailField label="Status" value={detail.loanAccount.status} />
            <DetailField label="Principal" value={formatINR(detail.loanAccount.principalAmount)} mono />
            <DetailField
              label="Tenure"
              value={
                detail.loanAccount.tenureMonths ? `${detail.loanAccount.tenureMonths} months` : null
              }
            />
            <DetailField
              label="Approved at"
              value={
                detail.loanAccount.approvedAt ? formatDateTime(detail.loanAccount.approvedAt) : null
              }
            />
            <DetailField
              label="Closed at"
              value={
                detail.loanAccount.closedAt ? formatDateTime(detail.loanAccount.closedAt) : null
              }
            />
          </dl>
          {detail.loanAccount.repaymentSchedule ? (
            <p className="text-foreground-muted text-xs">
              Schedule: {detail.loanAccount.repaymentSchedule.installmentCount ?? 0} installments of{" "}
              <span className="font-mono">
                {formatINR(detail.loanAccount.repaymentSchedule.installmentAmount)}
              </span>
              {detail.loanAccount.repaymentSchedule.firstDueDate
                ? `, first due ${detail.loanAccount.repaymentSchedule.firstDueDate}`
                : null}
            </p>
          ) : null}
        </section>
      ) : null}

      <MarkInvalidDialog
        open={markInvalidOpen}
        onOpenChange={setMarkInvalidOpen}
        applicationId={id}
        reasons={reasons}
        loadingReasons={loadingReasons}
        onSuccess={(next) => setDetail(next)}
      />
    </div>
  );
}

export default MyLoanDetailPage;
export const Component = MyLoanDetailPage;
