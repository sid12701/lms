/**
 * Phase 4 home dashboard — SYSTEM_ADMIN only (Gap #8).
 *
 * Non-admin roles redirect to their primary work surface via
 * `defaultLandingFor`. KPI cards live in `./components/*`; this file owns
 * wiring, loading, and error states.
 */
import { lazy, Suspense } from "react";
import { ShieldAlert } from "lucide-react";
import { PageHeader } from "@/components/app/layout/PageHeader";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { isUnauthorizedApiError } from "@/lib/api/api-errors";
import { KpiSkeleton, CardSkeleton, ChartSkeleton } from "@/components/app/feedback/Skeletons";
import { useSession } from "@/features/auth/session-context";
import { defaultLandingFor } from "@/lib/role-gates";
import { Navigate } from "react-router-dom";
import {
  InternalKpiSummary,
  OpenAlertsCard,
  RecentApplicationsCard,
} from "@/features/home/components";
import { useHomeKpis } from "./hooks/useHomeKpis";

const LoansByDpdBucketCard = lazy(() =>
  import("./components/LoansByDpdBucketCard").then((module) => ({
    default: module.LoansByDpdBucketCard,
  })),
);

function HomeLoadingSkeleton() {
  return (
    <div className="flex flex-col gap-6" data-testid="home-page-loading">
      <KpiSkeleton count={4} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <ChartSkeleton />
        <CardSkeleton lines={4} />
      </div>
      <CardSkeleton lines={6} />
    </div>
  );
}

export function HomePage() {
  const { session } = useSession();
  const role = session?.user.role;
  const query = useHomeKpis();

  if (role && role !== "SYSTEM_ADMIN") {
    return <Navigate to={defaultLandingFor(role)} replace />;
  }

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        title="Home"
        description="Operational summary across all LSPs — applications, repayments, alerts."
      />

      {query.isPending && query.data === undefined ? (
        <HomeLoadingSkeleton />
      ) : query.isError && isUnauthorizedApiError(query.error) ? (
        <EmptyState
          variant="no-permission"
          icon={ShieldAlert}
          title="No access to this dashboard"
          description="The operational home summary is restricted to system administrators."
        />
      ) : query.isError ? (
        <ErrorState
          title="Couldn't load your dashboard"
          description="The home metrics couldn't be fetched. Try again in a moment."
          retry={{
            label: "Retry",
            onClick: () => {
              void query.refetch();
            },
          }}
        />
      ) : query.data.kind === "internal" ? (
        <div data-testid="home-page-internal" className="flex flex-col gap-6">
          <InternalKpiSummary kpis={query.data.data} />
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <Suspense fallback={<ChartSkeleton />}>
              <LoansByDpdBucketCard buckets={query.data.data.dpdBuckets} />
            </Suspense>
            <OpenAlertsCard alerts={query.data.data.openAlerts} />
          </div>
          <RecentApplicationsCard applications={query.data.data.recentApplications} />
        </div>
      ) : null}
    </div>
  );
}

export default HomePage;
export const Component = HomePage;
