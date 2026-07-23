import { useCallback } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { FileText, ShieldAlert } from "lucide-react";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { isNotFoundApiError, isUnauthorizedApiError } from "@/lib/api/api-errors";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { RightRail } from "@/components/app/layout/RightRail";
import { StatusBadge } from "@/components/app/status/StatusBadge";
import { Skeleton } from "@/components/ui/skeleton";
import { Badge } from "@/components/ui/badge";
import { useSession } from "@/features/auth/session-context";
import { canPostRepayment } from "@/lib/role-gates";
import { formatINR } from "@/lib/format";
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
  WebhooksTab,
} from "./components/detail-tabs";
import { useLoanApplicationDetail } from "./hooks/useLoanApplicationDetail";
import { LoanApplicationDetailTab, type LoanApplicationDetail } from "./types";
import type { BorrowerDetail } from "@/features/borrowers/types";
import type { Role } from "@/schemas/role";

const REPAYABLE_STATUSES = new Set(["DISBURSED", "UNDER_REPAYMENT"]);

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
    <div data-slot="detail-skeleton" className="flex flex-col gap-6">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-48" />
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-5 w-32" />
      </div>
      <div className="flex gap-2">
        <Skeleton className="h-9 w-24" />
        <Skeleton className="h-9 w-24" />
      </div>
      <Skeleton className="h-48 w-full" />
    </div>
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
      return <DocumentsTab applicationId={applicationId} canManage={false} />;
    case "repayments":
      return <RepaymentsTab applicationId={applicationId} />;
    case "activity":
      return <ActivityTab applicationId={applicationId} />;
    case "webhooks":
      return <WebhooksTab applicationId={applicationId} />;
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
 * Per-tab fetches are gated by the active `?tab=` — Activity / Webhooks
 * queries stay paused until the user opens them so the cold-load only
 * pays for the header + Overview.
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
          <EmptyState
            variant="no-permission"
            icon={ShieldAlert}
            title="No access to this application"
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
        <section
          data-slot="detail-summary"
          className={cn("border-border bg-surface rounded-md border p-4", "flex flex-col gap-3")}
        >
          <h2 className="text-foreground text-sm font-semibold tracking-tight">At a glance</h2>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">Status</div>
            <StatusBadge
              status={detail.application.status}
              delinquency={detail.accountDelinquency}
            />
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">Requested</div>
            <div className="text-foreground text-base tabular-nums">
              {formatINR(detail.application.requestedAmount)}
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">LSP</div>
            <div className="text-foreground text-sm">{detail.lsp.name}</div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Badge
              className={cn(
                "border",
                detail.docsComplete
                  ? "border-success/30 bg-success/10 text-success"
                  : "border-warning/30 bg-warning/10 text-warning",
              )}
            >
              {detail.docsComplete ? "Docs complete" : "Docs incomplete"}
            </Badge>
            <Badge
              className={cn(
                "border",
                detail.scheduleValid
                  ? "border-success/30 bg-success/10 text-success"
                  : "border-warning/30 bg-warning/10 text-warning",
              )}
            >
              {detail.scheduleValid ? "Schedule valid" : "Schedule missing"}
            </Badge>
          </div>
        </section>
      </RightRail>
    </div>
  );
}

export default LoanApplicationDetailPage;
export const Component = LoanApplicationDetailPage;
