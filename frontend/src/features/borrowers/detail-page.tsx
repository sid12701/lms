import { useCallback, useMemo } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ShieldAlert, Users } from "lucide-react";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { isNotFoundApiError, isUnauthorizedApiError } from "@/lib/api/api-errors";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { RightRail } from "@/components/app/layout/RightRail";
import { Skeleton } from "@/components/ui/skeleton";
import { formatINR } from "@/lib/format";
import { cn } from "@/lib/utils";
import { BorrowerHeader } from "./components/BorrowerHeader";
import { BorrowerTabsShell } from "./components/BorrowerTabsShell";
import { ProfileTab, LoansTab } from "./components/tabs";
import { useBorrowerDetail } from "./hooks/useBorrowerDetail";
import { BorrowerDetailTab } from "./types";

/**
 * `?tab=` URL state. Mirrors the Phase 5 helper exactly — invalid or
 * missing values fall back to `"profile"`.
 */
function useBorrowerTabParam(): readonly [BorrowerDetailTab, (next: BorrowerDetailTab) => void] {
  const [params, setParams] = useSearchParams();
  const raw = params.get("tab");
  const parsed = BorrowerDetailTab.safeParse(raw);
  const active: BorrowerDetailTab = parsed.success ? parsed.data : "profile";

  const setTab = useCallback(
    (next: BorrowerDetailTab) => {
      setParams(
        (prev) => {
          const cloned = new URLSearchParams(prev);
          if (next === "profile") {
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
    <div data-slot="borrower-detail-skeleton" className="flex flex-col gap-6">
      <div className="flex flex-col gap-3">
        <Skeleton className="h-4 w-48" />
        <Skeleton className="h-8 w-72" />
        <Skeleton className="h-5 w-40" />
      </div>
      <div className="flex gap-2">
        <Skeleton className="h-9 w-24" />
        <Skeleton className="h-9 w-24" />
        <Skeleton className="h-9 w-24" />
      </div>
      <Skeleton className="h-64 w-full" />
    </div>
  );
}

/**
 * Phase-6 borrower-detail page.
 *
 * Layout mirrors the Phase 5 loan-application detail: PageHeader (inside
 * BorrowerHeader) → BorrowerTabsShell → right-rail "at a glance" with
 * borrower-level totals (only at xl per D6). Per-tab fetches stay paused
 * until the user opens that tab — the cold-load only pays for the header +
 * Profile.
 */
export function BorrowerDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const borrowerId = id ?? "";
  const detailQuery = useBorrowerDetail(borrowerId);
  const [activeTab, setActiveTab] = useBorrowerTabParam();

  const tabBody = useMemo(() => {
    if (!detailQuery.data) return null;
    const detail = detailQuery.data;
    switch (activeTab) {
      case "profile":
        return <ProfileTab detail={detail} />;
      case "loans":
        return <LoansTab borrowerId={borrowerId} />;
      default:
        return null;
    }
  }, [activeTab, borrowerId, detailQuery.data]);

  if (!borrowerId) {
    return (
      <div className="flex flex-col gap-6 p-6">
        <PermissionDeniedState
          title="Borrower not found"
          description="The URL is missing a borrower identifier."
          action={{ label: "Back to borrowers", onClick: () => navigate("/borrowers") }}
        />
      </div>
    );
  }

  if (detailQuery.isPending && detailQuery.data === undefined) {
    return (
      <div className="flex flex-col gap-6 p-6" data-testid="borrower-detail" data-loading="true">
        <DetailSkeleton />
      </div>
    );
  }

  if (detailQuery.isError) {
    if (isUnauthorizedApiError(detailQuery.error)) {
      return (
        <div
          className="flex flex-col gap-6 p-6"
          data-testid="borrower-detail"
          data-state="forbidden"
        >
          <EmptyState
            variant="no-permission"
            icon={ShieldAlert}
            title="No access to this borrower"
            description="You don't have permission to view this borrower record."
            action={{ label: "Back to borrowers", onClick: () => navigate("/borrowers") }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        </div>
      );
    }
    if (isNotFoundApiError(detailQuery.error)) {
      return (
        <div
          className="flex flex-col gap-6 p-6"
          data-testid="borrower-detail"
          data-state="not-found"
        >
          <PermissionDeniedState
            title="Borrower not found"
            description="This borrower either doesn't exist or you don't have permission to view them."
            action={{ label: "Back to borrowers", onClick: () => navigate("/borrowers") }}
            secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
          />
        </div>
      );
    }
    return (
      <div className="flex flex-col gap-6 p-6" data-testid="borrower-detail" data-state="error">
        <ErrorState
          icon={Users}
          title="Couldn't load this borrower"
          description={mapApiErrorMessage(detailQuery.error, "Please try again.")}
          retry={{ label: "Retry", onClick: () => void detailQuery.refetch() }}
        />
      </div>
    );
  }

  const detail = detailQuery.data;

  return (
    <div
      data-testid="borrower-detail"
      className="flex flex-col gap-6 p-6 xl:flex-row xl:items-start xl:gap-8"
    >
      <div className="min-w-0 flex-1 space-y-6">
        <BorrowerHeader detail={detail} />
        <BorrowerTabsShell activeTab={activeTab} onTabChange={setActiveTab}>
          {tabBody}
        </BorrowerTabsShell>
      </div>

      <RightRail>
        <section
          data-slot="borrower-summary"
          className={cn("border-border bg-surface rounded-md border p-4", "flex flex-col gap-3")}
        >
          <h2 className="text-foreground text-sm font-semibold tracking-tight">At a glance</h2>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">
              Open applications
            </div>
            <div className="text-foreground text-base tabular-nums">
              {detail.totals.openApplicationsCount}
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">
              Closed applications
            </div>
            <div className="text-foreground text-base tabular-nums">
              {detail.totals.closedApplicationsCount}
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">
              Lifetime disbursed
            </div>
            <div className="text-foreground text-base tabular-nums">
              {formatINR(detail.totals.lifetimeDisbursedAmount)}
            </div>
          </div>
          <div className="flex flex-col gap-2">
            <div className="text-foreground-muted text-xs tracking-wide uppercase">
              Active overdue
            </div>
            <div
              className={cn(
                "text-base tabular-nums",
                detail.totals.activeOverdueAmount > 0 ? "text-danger" : "text-foreground",
              )}
            >
              {formatINR(detail.totals.activeOverdueAmount)}
            </div>
          </div>
        </section>
      </RightRail>
    </div>
  );
}

export default BorrowerDetailPage;
export const Component = BorrowerDetailPage;
