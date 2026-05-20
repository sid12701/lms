/**
 * Phase 4 home dashboard.
 *
 * Dual-purpose: internal users (SYSTEM_ADMIN / OPS_USER / PRODUCT_ADMIN) see
 * the operational overview, while LSP users (LSP_UI_READ / LSP_UI_WRITE) see
 * their own portfolio summary. The shape returned by `useHomeKpis` is a
 * discriminated union keyed on `kind`, so the page only has to branch once.
 *
 * The cards themselves live in `./components/*` (owned by the cards agent);
 * this file owns wiring, loading + error states, and role-based branching.
 */
import { PageHeader } from "@/components/app/layout/PageHeader";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import {
  KpiSkeleton,
  CardSkeleton,
  ChartSkeleton,
} from "@/components/app/feedback/Skeletons";
import { useSession } from "@/features/auth/session-context";
import { isLspUiUser } from "@/lib/role-gates";
import {
  InternalKpiSummary,
  LoansByDpdBucketCard,
  LspKpiSummary,
  LspLinkCardGrid,
  OpenAlertsCard,
  RecentApplicationsCard,
} from "@/features/home/components";
import { useHomeKpis } from "./hooks/useHomeKpis";

const CARD_ENTRANCE =
  "motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-bottom-1 motion-safe:duration-300";

function HomeLoadingSkeleton({ lsp }: { lsp: boolean }) {
  return (
    <div className="flex flex-col gap-6" data-testid="home-page-loading">
      <KpiSkeleton count={lsp ? 4 : 4} />
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {lsp ? <CardSkeleton lines={4} /> : <ChartSkeleton />}
        <CardSkeleton lines={4} />
      </div>
      <CardSkeleton lines={6} />
    </div>
  );
}

export function HomePage() {
  const { session } = useSession();
  const role = session?.user.role;
  const lsp = role ? isLspUiUser(role) : false;
  const query = useHomeKpis();

  const eyebrow = lsp ? "LSP workspace" : "Internal workspace";
  const description = lsp
    ? "Quick view of your active loans, in-flight applications, and pending tasks."
    : "Operational summary across all LSPs — applications, repayments, alerts.";

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader eyebrow={eyebrow} title="Home" description={description} />

      {query.isPending ? (
        <HomeLoadingSkeleton lsp={lsp} />
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
        <div
          data-testid="home-page-internal"
          className="flex flex-col gap-6"
        >
          <div className={CARD_ENTRANCE}>
            <InternalKpiSummary kpis={query.data.data} />
          </div>
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className={CARD_ENTRANCE}>
              <LoansByDpdBucketCard buckets={query.data.data.dpdBuckets} />
            </div>
            <div className={CARD_ENTRANCE}>
              <OpenAlertsCard alerts={query.data.data.openAlerts} />
            </div>
          </div>
          <div className={CARD_ENTRANCE}>
            <RecentApplicationsCard applications={query.data.data.recentApplications} />
          </div>
        </div>
      ) : (
        <div data-testid="home-page-lsp" className="flex flex-col gap-6">
          <div className={CARD_ENTRANCE}>
            <LspKpiSummary kpis={query.data.data} />
          </div>
          <div className={CARD_ENTRANCE}>
            <LspLinkCardGrid />
          </div>
          <div className={CARD_ENTRANCE}>
            <RecentApplicationsCard applications={query.data.data.recentApplications} />
          </div>
        </div>
      )}
    </div>
  );
}

export default HomePage;
export const Component = HomePage;
