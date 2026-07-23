import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { AlertTriangle, ArrowLeft, ChevronRight, Loader2 } from "lucide-react";

import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { Button } from "@/components/ui/button";
import { useSession } from "@/features/auth/session-context";
import { formatDateTime, formatINR } from "@/lib/format";
import { hasPermission } from "@/lib/permissions";
import type { MyLoanDetail } from "./api";
import type { LoanStatus } from "@/types";
import { DocumentsSection } from "./components/DocumentsSection";
import { LoanServicingPanel } from "./components/LoanServicingPanel";
import { DetailField } from "./components/DetailField";
import { MarkInvalidDialog } from "./components/MarkInvalidDialog";
import { MaskedBorrowerCard } from "./components/MaskedBorrowerCard";
import { safeApiMessage } from "./utils";
import { myLoanDetailQueryKey, useMyLoanDetail } from "./hooks/useMyLoanDetail";
import { useInvalidReasons } from "./hooks/useInvalidReasons";

const TERMINAL_STATUSES = new Set<LoanStatus>(["INVALID", "REJECTED", "CLOSED", "FORECLOSED"]);

function LoanTermsCard({ detail }: { detail: MyLoanDetail }) {
  return (
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
  );
}

function BorrowerContactCard({ detail }: { detail: MyLoanDetail }) {
  const location = [detail.borrowerCity, detail.borrowerState].filter(Boolean).join(", ");
  return (
    <section
      data-slot="borrower-contact-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <h2 className="text-base font-semibold">Borrower contact</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <DetailField label="Mobile" value={detail.borrowerMobile} mono />
        <DetailField label="Email" value={detail.borrowerEmail} />
        <DetailField label="Date of birth" value={detail.borrowerDob} mono />
        <DetailField label="Location" value={location || null} />
      </dl>
    </section>
  );
}

function RecentActivityCard({ detail }: { detail: MyLoanDetail }) {
  const activity = detail.lastActivity;
  if (!activity) return null;

  return (
    <section
      data-slot="loan-activity-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <h2 className="text-base font-semibold">Recent activity</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <DetailField label="Type" value={activity.activityType} />
        <DetailField label="Summary" value={activity.summary} />
        <DetailField label="Actor" value={activity.actorUsername} />
        <DetailField label="Occurred at" value={formatDateTime(activity.occurredAt)} />
      </dl>
      {activity.detail ? <p className="text-foreground-muted text-xs">{activity.detail}</p> : null}
    </section>
  );
}

function LoanAccountCard({ detail }: { detail: MyLoanDetail }) {
  const account = detail.loanAccount;
  if (!account) return null;

  return (
    <section
      data-slot="loan-account-card"
      className="border-border bg-background flex flex-col gap-4 rounded-md border p-5"
    >
      <h2 className="text-base font-semibold">Loan account</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
        <DetailField label="Account number" value={account.accountNumber} mono />
        <DetailField label="Status" value={account.status} />
        <DetailField label="Principal" value={formatINR(account.principalAmount)} mono />
        <DetailField
          label="Tenure"
          value={account.tenureMonths ? `${account.tenureMonths} months` : null}
        />
        <DetailField
          label="Approved at"
          value={account.approvedAt ? formatDateTime(account.approvedAt) : null}
        />
        <DetailField
          label="Closed at"
          value={account.closedAt ? formatDateTime(account.closedAt) : null}
        />
        {account.closureReason ? (
          <DetailField label="Closure reason" value={account.closureReason} />
        ) : null}
      </dl>
      {account.delinquency ? (
        <div className="border-border bg-surface-muted rounded-md border p-3 text-sm">
          <h3 className="mb-2 font-medium">Delinquency</h3>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            <DetailField label="Bucket" value={account.delinquency.bucket} />
            <DetailField
              label="Overdue amount"
              value={formatINR(account.delinquency.overdueAmount)}
              mono
            />
            <DetailField
              label="Overdue installments"
              value={
                account.delinquency.overdueInstallmentCount != null
                  ? String(account.delinquency.overdueInstallmentCount)
                  : null
              }
            />
            <DetailField
              label="Max days past due"
              value={
                account.delinquency.maxDaysPastDue != null
                  ? String(account.delinquency.maxDaysPastDue)
                  : null
              }
            />
          </dl>
        </div>
      ) : null}
      {account.disbursement ? (
        <div className="border-border bg-surface-muted rounded-md border p-3 text-sm">
          <h3 className="mb-2 font-medium">Disbursement</h3>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            <DetailField label="Status" value={account.disbursement.status} />
            <DetailField
              label="Gross amount"
              value={formatINR(account.disbursement.grossAmount)}
              mono
            />
            <DetailField
              label="Net disbursed"
              value={formatINR(account.disbursement.netDisbursedAmount)}
              mono
            />
            <DetailField
              label="Disbursed at"
              value={
                account.disbursement.disbursedAt
                  ? formatDateTime(account.disbursement.disbursedAt)
                  : null
              }
            />
            {account.disbursement.failureReason ? (
              <DetailField label="Failure reason" value={account.disbursement.failureReason} />
            ) : null}
          </dl>
        </div>
      ) : null}
      {account.repaymentSchedule ? (
        <p className="text-foreground-muted text-xs">
          Schedule: {account.repaymentSchedule.installmentCount ?? 0} installments of{" "}
          <span className="font-mono">
            {formatINR(account.repaymentSchedule.installmentAmount)}
          </span>
          {account.repaymentSchedule.firstDueDate
            ? `, first due ${account.repaymentSchedule.firstDueDate}`
            : null}
        </p>
      ) : null}
    </section>
  );
}

export function MyLoanDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { session } = useSession();
  const [markInvalidOpen, setMarkInvalidOpen] = useState(false);
  const applicationId = id ?? "";
  const detailQuery = useMyLoanDetail(applicationId);
  const reasonsQuery = useInvalidReasons(markInvalidOpen);
  const queryClient = useQueryClient();

  if (!id) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        <EmptyState
          icon={AlertTriangle}
          title="Missing loan id"
          description="The URL did not include a loan identifier."
          action={{ label: "Back to loan applications", onClick: () => navigate("/my-loans") }}
        />
      </div>
    );
  }

  if (detailQuery.isPending && detailQuery.data === undefined) {
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

  const detail = detailQuery.data;
  if (detailQuery.isError || !detail) {
    const errorMessage = detailQuery.isError
      ? safeApiMessage(detailQuery.error, "Failed to load loan.")
      : null;
    const notFound = errorMessage?.toLowerCase().includes("not found");
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader eyebrow="LSP workspace" title="Loan" />
        {notFound ? (
          <PermissionDeniedState
            title="Loan not found"
            description="This loan may have been removed or is outside your LSP scope."
            action={{ label: "Back to loan applications", onClick: () => navigate("/my-loans") }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        ) : (
          <ErrorState
            title="Couldn't load this loan"
            description={errorMessage ?? "Try again in a moment."}
            retry={{
              label: "Retry",
              onClick: () => void detailQuery.refetch(),
            }}
          />
        )}
      </div>
    );
  }

  const isTerminal = TERMINAL_STATUSES.has(detail.status);
  const canWriteLoan = session ? hasPermission(session.user.role, "LOAN_WRITE") : false;
  const canMutateLoan = canWriteLoan && !isTerminal;
  const documentsReadOnlyReason = canMutateLoan ? null : isTerminal ? "terminal" : "role";
  const eyebrow = detail.externalLoanId
    ? `LSP workspace · ${detail.externalLoanId}`
    : "LSP workspace";

  return (
    <div className="flex flex-col gap-6 p-6">
      <nav aria-label="Breadcrumb" className="text-foreground-muted text-xs">
        <Link to="/my-loans" className="hover:text-foreground inline-flex items-center gap-1">
          <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5" />
          <span>Loan applications</span>
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
            {canMutateLoan ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setMarkInvalidOpen(true)}
              >
                <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                <span>Mark invalid</span>
              </Button>
            ) : null}
          </div>
        }
      />

      <LoanTermsCard detail={detail} />
      <BorrowerContactCard detail={detail} />

      <MaskedBorrowerCard detail={detail} />

      <DocumentsSection
        applicationId={applicationId}
        canUpload={canMutateLoan}
        readOnlyReason={documentsReadOnlyReason}
      />

      <RecentActivityCard detail={detail} />
      <LoanAccountCard detail={detail} />

      {detail.loanAccount ? (
        <LoanServicingPanel key={detail.loanAccount.id} loanAccountId={detail.loanAccount.id} />
      ) : null}

      <MarkInvalidDialog
        open={markInvalidOpen}
        onOpenChange={setMarkInvalidOpen}
        applicationId={applicationId}
        reasons={reasonsQuery.data ?? []}
        loadingReasons={reasonsQuery.isPending}
        reasonsLoadError={
          reasonsQuery.isError
            ? safeApiMessage(reasonsQuery.error, "Could not load invalid reasons.")
            : null
        }
        onSuccess={(next) => {
          queryClient.setQueryData(myLoanDetailQueryKey(applicationId), next);
        }}
      />
    </div>
  );
}

export default MyLoanDetailPage;
export const Component = MyLoanDetailPage;
