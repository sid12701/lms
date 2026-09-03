import { useState, useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import { AlertTriangle, ArrowLeft } from "lucide-react";

import { PageHeader } from "@/components/app/layout/PageHeader";
import { CardSkeleton } from "@/components/app/feedback/Skeletons";
import { usePageMeta } from "@/components/app/shell/page-meta-context";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { AbsoluteRelativeTime } from "@/components/app/misc/AbsoluteRelativeTime";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { apiAccountStatus, disbursementStatusLabel } from "@/components/app/status/statusBadgeMeta";
import { closureReasonLabel } from "@/schemas/loan-account";
import { TransitionDisabledTooltip } from "@/components/app/lifecycle/TransitionDisabledTooltip";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useSession } from "@/features/auth/session-context";
import { formatDateTime, formatINR } from "@/lib/format";
import { delinquencyBucketLabel } from "@/lib/delinquency-display";
import { formatLoanDocumentTitle, resolveLoanPageIdentity } from "@/lib/loan-page-identity";
import { formatActivitySummary, formatActivityType } from "@/lib/loan-application-activity";
import { canTransition } from "@/lib/lifecycle";
import { hasPermission } from "@/lib/permissions";
import type { MyLoanDetail } from "./api";
import type { LoanStatus, Role } from "@/types";
import { DocumentsSection } from "./components/DocumentsSection";
import { LoanServicingPanel } from "./components/LoanServicingPanel";
import { DetailField } from "./components/DetailField";
import { MarkInvalidDialog } from "./components/MarkInvalidDialog";
import { MaskedBorrowerCard } from "./components/MaskedBorrowerCard";
import { safeApiMessage } from "./utils";
import { myLoanDetailQueryKey, useMyLoanDetail } from "./hooks/useMyLoanDetail";
import { useInvalidReasons } from "./hooks/useInvalidReasons";

const TERMINAL_STATUSES = new Set<LoanStatus>(["INVALID", "REJECTED", "CLOSED", "FORECLOSED"]);

/** Ties the disabled "Mark invalid" button to its reason for assistive tech. */
const MARK_INVALID_REASON_ID = "mark-invalid-disabled-reason";

/**
 * Why the "Mark invalid" action is unavailable, or null when it is actionable.
 *
 * `canTransition` is the gate — `PRE_DISBURSAL_INVALIDATABLE` in `lib/lifecycle`
 * mirrors the backend's `LoanApplicationStatus.canTransitionTo`, so a loan that
 * has been disbursed can never be invalidated. Invalidation rules carry no
 * preconditions, so an empty `TransitionCtx` is sufficient here.
 *
 * Callers render this only for non-terminal loans, so the sole way to fail the
 * gate is money already in motion (DISBURSED, UNDER_REPAYMENT). The wording
 * mirrors the backend's own rejection message.
 */
function resolveMarkInvalidDisabledReason(role: Role, status: LoanStatus): string | null {
  return canTransition(role, status, "INVALID", {}).ok
    ? null
    : "Loans that have entered servicing can't be marked invalid.";
}

function LoanTermsCard({ detail }: { detail: MyLoanDetail }) {
  return (
    <section
      data-slot="loan-terms-card"
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
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
        <div className="border-warning/30 bg-warning/5 text-warning rounded-container border px-3 py-2 text-xs">
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
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
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
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
    >
      <h2 className="text-base font-semibold">Recent activity</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <DetailField label="Type" value={formatActivityType(activity.activityType)} />
        <DetailField
          label="Summary"
          value={formatActivitySummary(activity.summary, activity.activityType)}
        />
        <DetailField label="Actor" value={activity.actorUsername} />
        <DetailField
          label="Occurred at"
          value={<AbsoluteRelativeTime iso={activity.occurredAt} />}
        />
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
      className="border-border bg-background rounded-container flex flex-col gap-4 border p-5"
    >
      <h2 className="text-base font-semibold">Loan account</h2>
      <dl className="grid grid-cols-1 gap-x-6 gap-y-3 text-sm sm:grid-cols-3">
        <DetailField label="Account number" value={account.accountNumber} mono />
        {/* The account status is a badge everywhere else in the product, and
            DESIGN.md treats that as an invariant — the partner surface used to
            print the bare enum instead. `apiAccountStatus` keeps drift visible
            as `Unknown (raw)` rather than passing the backend spelling through
            as if it were a label. */}
        <DetailField
          label="Status"
          value={account.status ? <StatusBadge status={apiAccountStatus(account.status)} /> : null}
        />
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
          <DetailField label="Closure reason" value={closureReasonLabel(account.closureReason)} />
        ) : null}
      </dl>
      {account.delinquency ? (
        <div className="border-border bg-surface-muted rounded-container border p-3 text-sm">
          <h3 className="mb-2 font-medium">Delinquency</h3>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            <DetailField
              label="Bucket"
              value={
                account.delinquency.bucket
                  ? delinquencyBucketLabel(account.delinquency.bucket)
                  : null
              }
            />
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
        <div className="border-border bg-surface-muted rounded-container border p-3 text-sm">
          <h3 className="mb-2 font-medium">Disbursement</h3>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            {/* Text rather than a badge: unlike the account status above, a
                disbursement verdict carries no badge anywhere in the product,
                and inventing one here would make the partner surface the only
                place it exists. */}
            <DetailField
              label="Status"
              value={disbursementStatusLabel(account.disbursement.status)}
            />
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

function MyLoanDetailSkeleton() {
  return (
    <div
      data-slot="my-loan-detail-skeleton"
      className="flex flex-col gap-6"
      role="status"
      aria-label="Loading loan details"
    >
      <Skeleton className="h-3 w-44" />
      <div className="flex flex-col gap-2">
        <Skeleton className="h-3 w-36" />
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-4 w-52" />
      </div>
      <CardSkeleton lines={4} />
      <CardSkeleton lines={3} />
      <CardSkeleton lines={5} />
      <CardSkeleton lines={4} />
    </div>
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
  const detail = detailQuery.data;

  const pageIdentity = useMemo(() => {
    if (!detail) return null;
    const identity = resolveLoanPageIdentity({
      applicationId,
      externalLoanId: detail.externalLoanId,
      borrowerName: detail.borrowerFullName,
    });
    return {
      breadcrumbLabel: identity,
      documentTitle: formatLoanDocumentTitle(identity),
    };
  }, [applicationId, detail]);

  usePageMeta(pageIdentity ?? {});

  if (!id) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader title="Loan" />
        <EmptyState
          icon={AlertTriangle}
          title="Missing loan id"
          description="The URL did not include a loan identifier."
          action={{ label: "Back to my loans", onClick: () => navigate("/my-loans") }}
        />
      </div>
    );
  }

  if (detailQuery.isPending && detailQuery.data === undefined) {
    return (
      <div className="flex flex-col gap-6 p-6">
        {/*
          A skeleton bar is not a heading, so this branch used to leave the
          route with no `h1` at all for the whole load — the same gap F-26
          reported on the permission-denied screen. sr-only because the visible
          title is the borrower's name, which is precisely what has not arrived
          yet; `routes/not-found` and `routes/route-fallback` resolve it the
          same way.
        */}
        <h1 className="sr-only">Loading loan</h1>
        <MyLoanDetailSkeleton />
      </div>
    );
  }

  if (detailQuery.isError || !detail) {
    const errorMessage = detailQuery.isError
      ? safeApiMessage(detailQuery.error, "Failed to load loan.")
      : null;
    const notFound = errorMessage?.toLowerCase().includes("not found");
    if (notFound) {
      // No `PageHeader` here: `PermissionDeniedState` already titles itself
      // with an h1, and "Loan not found" is the truer heading for a loan we
      // cannot show. A generic "Loan" header above it would claim a loan is on
      // screen *and* give the route a second h1. The sibling application and
      // borrower detail pages resolve their not-found branch the same way.
      return (
        <div className="flex flex-col gap-6 p-6">
          <PermissionDeniedState
            title="Loan not found"
            description="This loan may have been removed or is outside your LSP scope."
            action={{ label: "Back to my loans", onClick: () => navigate("/my-loans") }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        </div>
      );
    }
    return (
      <div className="flex flex-col gap-6 p-6">
        <PageHeader title="Loan" />
        <ErrorState
          title="Couldn't load this loan"
          description={errorMessage ?? "Try again in a moment."}
          retry={{
            label: "Retry",
            onClick: () => void detailQuery.refetch(),
          }}
        />
      </div>
    );
  }

  const isTerminal = TERMINAL_STATUSES.has(detail.status);
  const canWriteLoan = session ? hasPermission(session.user.role, "LOAN_WRITE") : false;
  const canMutateLoan = canWriteLoan && !isTerminal;
  const documentsReadOnlyReason = canMutateLoan ? null : isTerminal ? "terminal" : "role";
  // Document upload and invalidation are separate rights: uploading stays open
  // for the life of a non-terminal loan, while invalidation closes at
  // disbursement. Gate them independently rather than on one `canMutateLoan`.
  const markInvalidDisabledReason =
    session && canWriteLoan
      ? resolveMarkInvalidDisabledReason(session.user.role, detail.status)
      : null;
  const eyebrow = detail.externalLoanId
    ? `LSP workspace · ${detail.externalLoanId}`
    : "LSP workspace";

  return (
    <div className="flex flex-col gap-6 p-6">
      {/*
        A back link, not a second breadcrumb. The shell's `BreadcrumbBar`
        already renders the only `aria-label="Breadcrumb"` landmark and, via
        `usePageMeta` above, already ends on this loan's id — duplicating it
        here gave screen readers two identically-named landmarks with
        contradictory trails. The one thing the shell does not offer is a
        one-tap return to the list, so that is all this keeps.
      */}
      <Link
        to="/my-loans"
        className="text-foreground-muted hover:text-foreground inline-flex w-fit items-center gap-1 text-xs"
      >
        <ArrowLeft aria-hidden="true" className="h-3.5 w-3.5" />
        <span>Back to my loans</span>
      </Link>

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
            {canWriteLoan && !isTerminal ? (
              <>
                <TransitionDisabledTooltip disabledReason={markInvalidDisabledReason}>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    aria-describedby={
                      markInvalidDisabledReason ? MARK_INVALID_REASON_ID : undefined
                    }
                    onClick={() => setMarkInvalidOpen(true)}
                  >
                    <AlertTriangle className="h-4 w-4" aria-hidden="true" />
                    <span>Mark invalid</span>
                  </Button>
                </TransitionDisabledTooltip>
                {/* A disabled button is not focusable, so the tooltip alone is
                    unreachable by keyboard and screen reader. The reason is
                    mirrored here so assistive tech can read it off the button. */}
                {markInvalidDisabledReason ? (
                  <span id={MARK_INVALID_REASON_ID} className="sr-only">
                    {markInvalidDisabledReason}
                  </span>
                ) : null}
              </>
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
