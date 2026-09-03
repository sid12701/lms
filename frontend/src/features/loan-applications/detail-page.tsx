import { useCallback, useMemo } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { FileText, ShieldAlert } from "lucide-react";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { isNotFoundApiError, isUnauthorizedApiError } from "@/lib/api/api-errors";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { RightRail } from "@/components/app/layout/RightRail";
import { CardSkeleton } from "@/components/app/feedback/Skeletons";
import { usePageMeta } from "@/components/app/shell/page-meta-context";
import { formatLoanDocumentTitle, resolveLoanPageIdentity } from "@/lib/loan-page-identity";
import { Skeleton } from "@/components/ui/skeleton";
import { useSession } from "@/features/auth/session-context";
import { canPostRepayment } from "@/lib/role-gates";
import { cn } from "@/lib/utils";
import { useBorrowerDetail } from "@/features/borrowers/hooks/useBorrowerDetail";
import { DetailHeader } from "./components/DetailHeader";
import { DetailTabsShell } from "./components/DetailTabsShell";
import {
  ActivityTab,
  DocumentsTab,
  OverviewTab,
  RepaymentsTab,
  ScheduleTab,
} from "./components/detail-tabs";
import { useLoanApplicationDetail } from "./hooks/useLoanApplicationDetail";
import { LoanApplicationDetailTab, type LoanApplicationDetail } from "./types";
import type { BorrowerDetail } from "@/features/borrowers/types";
import type { Role } from "@/schemas/role";

const REPAYABLE_STATUSES = new Set(["DISBURSED", "UNDER_REPAYMENT"]);

/**
 * Statuses where the money has not left the LSP's disbursal account yet, so
 * there is nothing to repay and no repayment health to report.
 *
 * "At a glance" used to fall through to "Repayment · On track" for these,
 * which read as reassurance on the two screens where it is least warranted —
 * an approved loan awaiting disbursal, and one the automated worker has given
 * up on. `REJECTED` and `INVALID` are deliberately absent: they will never
 * disburse, so "awaiting disbursement" would be just as untrue.
 */
const PRE_DISBURSEMENT_STATUSES = new Set([
  "INITIALIZED",
  "AWAITING_APPROVAL",
  "APPROVED_PENDING_DISBURSAL",
  "DISBURSEMENT_RETRY",
]);

/**
 * `?tab=` URL state. Mirrors the type from `./types.ts` — invalid or
 * missing values fall back to `"overview"`.
 */
function useTabParam(): readonly [
  LoanApplicationDetailTab,
  (next: LoanApplicationDetailTab) => void,
] {
  const [params, setParams] = useSearchParams();
  const raw = params.get("tab");
  const parsed = LoanApplicationDetailTab.safeParse(raw);
  const active: LoanApplicationDetailTab = parsed.success ? parsed.data : "overview";

  const setTab = useCallback(
    (next: LoanApplicationDetailTab) => {
      setParams(
        (prev) => {
          const cloned = new URLSearchParams(prev);
          if (next === "overview") {
            cloned.delete("tab");
          } else {
            cloned.set("tab", next);
          }
          return cloned;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  return [active, setTab] as const;
}

function DetailSkeleton() {
  return (
    <div
      data-slot="detail-skeleton"
      className="flex flex-col gap-6 xl:flex-row xl:items-start xl:gap-8"
      role="status"
      aria-label="Loading loan application"
    >
      {/*
        Every route in this app exposes exactly one `h1`; while the detail data
        is in flight there was none at all, so a screen-reader user had no page
        identity on a slow load. `my-loans` already solved it this way.
      */}
      <h1 className="sr-only">Loading loan application</h1>
      <div className="min-w-0 flex-1 space-y-6">
        <div className="flex flex-col gap-4">
          <Skeleton className="h-3 w-48" />
          <Skeleton className="h-8 w-72" />
          <Skeleton className="h-4 w-40" />
          <div className="flex flex-wrap gap-2">
            <Skeleton className="h-9 w-28" />
            <Skeleton className="h-9 w-28" />
            <Skeleton className="h-9 w-28" />
          </div>
        </div>
        <div className="flex flex-col gap-4">
          <div className="border-border flex flex-wrap gap-2 border-b pb-2">
            {Array.from({ length: 6 }).map((_, index) => (
              <Skeleton key={index} className="h-8 w-20" />
            ))}
          </div>
          <CardSkeleton lines={6} />
        </div>
      </div>
      <div className="hidden w-72 shrink-0 xl:block">
        <CardSkeleton lines={4} />
      </div>
    </div>
  );
}

function DetailRightRail({
  detail,
}: {
  detail: LoanApplicationDetail;
  borrowerDetail: BorrowerDetail | null;
}) {
  const delinquency = detail.accountDelinquency;
  const interestRate = detail.interestRate != null ? `${detail.interestRate}%` : null;

  return (
    <section
      data-slot="detail-summary"
      className={cn("border-border bg-surface rounded-container border p-4", "flex flex-col gap-4")}
    >
      <h2 className="text-foreground text-sm font-semibold tracking-tight">At a glance</h2>
      {delinquency && delinquency.maxDaysPastDue != null && delinquency.maxDaysPastDue > 0 ? (
        <div className="flex flex-col gap-1">
          <div className="text-foreground-muted text-xs tracking-wide uppercase">Delinquency</div>
          <div className="text-foreground text-sm tabular-nums">
            {delinquency.maxDaysPastDue} day{delinquency.maxDaysPastDue === 1 ? "" : "s"} past due
          </div>
          {delinquency.overdueInstallmentCount != null ? (
            <div className="text-foreground-muted text-xs">
              {delinquency.overdueInstallmentCount} overdue installment
              {delinquency.overdueInstallmentCount === 1 ? "" : "s"}
            </div>
          ) : null}
        </div>
      ) : PRE_DISBURSEMENT_STATUSES.has(detail.application.status) ? (
        <div className="flex flex-col gap-1">
          <div className="text-foreground-muted text-xs tracking-wide uppercase">Repayment</div>
          <div className="text-foreground text-sm">Awaiting disbursement</div>
        </div>
      ) : (
        <div className="flex flex-col gap-1">
          <div className="text-foreground-muted text-xs tracking-wide uppercase">Repayment</div>
          <div className="text-foreground text-sm">On track</div>
        </div>
      )}
      <div className="flex flex-col gap-1">
        <div className="text-foreground-muted text-xs tracking-wide uppercase">Documents</div>
        <div className="text-foreground text-sm">
          {detail.docsComplete ? "Docs complete" : "Docs incomplete"}
        </div>
      </div>
      <div className="flex flex-col gap-1">
        <div className="text-foreground-muted text-xs tracking-wide uppercase">Schedule</div>
        <div className="text-foreground text-sm">
          {detail.scheduleValid ? "Schedule valid" : "Schedule invalid"}
        </div>
      </div>
      {interestRate ? (
        <div className="flex flex-col gap-1">
          <div className="text-foreground-muted text-xs tracking-wide uppercase">Interest rate</div>
          <div className="text-foreground text-sm tabular-nums">{interestRate}</div>
        </div>
      ) : null}
    </section>
  );
}

function ApplicationTabBody({
  activeTab,
  applicationId,
  detail,
  borrowerDetail,
  role,
}: {
  activeTab: LoanApplicationDetailTab;
  applicationId: string;
  detail: LoanApplicationDetail;
  borrowerDetail: BorrowerDetail | null;
  role: Role | undefined;
}) {
  const canPost =
    role !== undefined &&
    canPostRepayment(role) &&
    REPAYABLE_STATUSES.has(detail.application.status);

  switch (activeTab) {
    case "overview":
      return <OverviewTab detail={detail} borrowerDetail={borrowerDetail} />;
    case "schedule":
      return (
        <ScheduleTab
          applicationId={applicationId}
          status={detail.application.status}
          canPost={canPost}
          docsComplete={detail.docsComplete}
          scheduleValid={detail.scheduleValid}
        />
      );
    case "documents":
      return (
        <DocumentsTab
          applicationId={applicationId}
          canManage={false}
          loanStatus={detail.application.status}
        />
      );
    case "repayments":
      return <RepaymentsTab applicationId={applicationId} />;
    case "activity":
      return <ActivityTab applicationId={applicationId} />;
  }
}

/**
 * Phase-5 loan-application detail page.
 *
 * Layout: PageHeader (inside DetailHeader) → DetailTabsShell → right rail
 * at xl. The right rail collapses below xl per D6; the same summary is not
 * duplicated as a tab here because the Overview tab already carries the
 * full context (the rail is a "stuck above the fold" convenience).
 *
 * Per-tab fetches are gated by the active `?tab=` — the Activity query
 * stays paused until the user opens it so the cold-load only pays for
 * the header + Overview.
 */
export function LoanApplicationDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const applicationId = id ?? "";
  const detailQuery = useLoanApplicationDetail(applicationId);
  const [activeTab, setActiveTab] = useTabParam();
  const { session } = useSession();
  const role = session?.user.role;

  // Gap #20: fetch the borrower-admin projection in parallel with the
  // loan-app detail so the OverviewTab can render the fuller borrower
  // card (visible LSPs + delinquency totals) once the second query
  // resolves. Falls back to the loan-app's embedded thin projection
  // while pending or on error.
  const borrowerId = detailQuery.data?.borrower.id ?? "";
  const borrowerDetailQuery = useBorrowerDetail(borrowerId);
  const borrowerDetail =
    borrowerDetailQuery.data && borrowerId.length > 0 ? borrowerDetailQuery.data : null;

  const pageIdentity = useMemo(() => {
    if (!detailQuery.data) return null;
    const identity = resolveLoanPageIdentity({
      applicationId: detailQuery.data.application.id,
      externalLoanId: detailQuery.data.application.externalLoanId,
      borrowerName: detailQuery.data.borrower.fullName,
    });
    return {
      breadcrumbLabel: identity,
      documentTitle: formatLoanDocumentTitle(identity),
    };
  }, [detailQuery.data]);

  usePageMeta(pageIdentity ?? {});

  if (!applicationId) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PermissionDeniedState
          title="No application id"
          description="The URL is missing an application identifier."
          action={{ label: "Back to applications", onClick: () => navigate("/loan-applications") }}
        />
      </div>
    );
  }

  if (detailQuery.isPending && detailQuery.data === undefined) {
    return (
      <div
        className="flex flex-col gap-6 p-6"
        data-testid="loan-application-detail"
        data-loading="true"
      >
        <DetailSkeleton />
      </div>
    );
  }

  if (detailQuery.isError) {
    if (isUnauthorizedApiError(detailQuery.error)) {
      return (
        <div
          className="flex flex-col gap-6 p-6"
          data-testid="loan-application-detail"
          data-state="forbidden"
        >
          {/* Whole-page state with no `PageHeader` above it, so this title is
              the route's only heading — `EmptyState` defaults to a `p`. */}
          <EmptyState
            variant="no-permission"
            icon={ShieldAlert}
            title="No access to this application"
            titleAs="h1"
            description="You don't have permission to view this loan application."
            action={{
              label: "Back to applications",
              onClick: () => navigate("/loan-applications"),
            }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        </div>
      );
    }
    if (isNotFoundApiError(detailQuery.error)) {
      return (
        <div
          className="flex flex-col gap-6 p-6"
          data-testid="loan-application-detail"
          data-state="not-found"
        >
          <PermissionDeniedState
            title="Application not found"
            description="This loan application either doesn't exist or you don't have permission to view it."
            action={{
              label: "Back to applications",
              onClick: () => navigate("/loan-applications"),
            }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        </div>
      );
    }
    return (
      <div
        className="flex flex-col gap-6 p-6"
        data-testid="loan-application-detail"
        data-state="error"
      >
        <ErrorState
          icon={FileText}
          title="Couldn't load this application"
          description={mapApiErrorMessage(detailQuery.error, "Please try again.")}
          retry={{ label: "Retry", onClick: () => void detailQuery.refetch() }}
        />
      </div>
    );
  }

  const detail = detailQuery.data;

  return (
    <div
      data-testid="loan-application-detail"
      className="flex flex-col gap-6 p-6 xl:flex-row xl:items-start xl:gap-8"
    >
      <div className="min-w-0 flex-1 space-y-6">
        <DetailHeader detail={detail} />
        <DetailTabsShell activeTab={activeTab} onTabChange={setActiveTab}>
          <ApplicationTabBody
            activeTab={activeTab}
            applicationId={applicationId}
            detail={detail}
            borrowerDetail={borrowerDetail}
            role={role}
          />
        </DetailTabsShell>
      </div>

      <RightRail>
        <DetailRightRail detail={detail} borrowerDetail={borrowerDetail} />
      </RightRail>
    </div>
  );
}

export default LoanApplicationDetailPage;
export const Component = LoanApplicationDetailPage;
