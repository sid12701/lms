import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ContentState } from '@/components/app/content-state'
import { Button } from '@/components/ui/button'
import { queryKeys } from '@/features/api/query-keys'
import { getPortfolioMisSummary } from '@/features/api/reports-api'
import { useAuth } from '@/features/auth/auth-context'
import { isLspUiUser } from '@/features/auth/role-utils'
import { AdminHomeDashboard } from './admin-home-dashboard'
import { buildHomeLinks } from './home-config'
import { HomeLinkCard } from './home-link-card'
import { useHomeOverview } from './use-home-overview'

function SessionLoadingState() {
  return (
    <section className="space-y-6">
      <ContentState
        title="Loading session"
        description="Preparing the workspace for the current account."
        tone="loading"
      />
    </section>
  )
}

function AdminDashboardState({
  loading,
  error,
}: {
  loading: boolean
  error: string
}) {
  const title = loading ? 'Loading operations dashboard' : 'Unable to load operations dashboard'
  const description = loading
    ? 'Fetching live portfolio overview, MIS summary, and priority accounts.'
    : error

  return (
    <ContentState
      title={title}
      description={description}
      tone={loading ? 'loading' : 'error'}
    />
  )
}

export function HomePage() {
  const { user } = useAuth()
  const roles = user?.roles ?? []
  const lspUser = isLspUiUser(roles)
  const adminUser = Boolean(user) && !lspUser
  const links = useMemo(() => buildHomeLinks(user), [user])
  const { overview, loading: overviewLoading, error: overviewError } = useHomeOverview(adminUser)
  const summaryQuery = useQuery({
    queryKey: queryKeys.portfolioMisSummary({}),
    queryFn: () => getPortfolioMisSummary(),
    enabled: adminUser,
  })
  const priorityAccounts = useMemo(
    () =>
      [...(overview?.priorityAccounts ?? [])]
        .sort((left, right) => {
          if (right.daysPastDue !== left.daysPastDue) {
            return right.daysPastDue - left.daysPastDue
          }

          return right.overdueAmount - left.overdueAmount
        })
        .slice(0, 8),
    [overview?.priorityAccounts],
  )

  if (!user) {
    return <SessionLoadingState />
  }

  if (adminUser) {
    const dashboardError =
      summaryQuery.error instanceof Error
        ? summaryQuery.error.message
        : ''
    const adminError = overviewError || dashboardError
    const adminLoading = overviewLoading || summaryQuery.isLoading

    return (
      <section className="space-y-6">
        <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-2">
            <h1 className="font-heading text-3xl font-bold tracking-[-0.03em] text-primary lg:text-4xl">
              Dashboard Summary
            </h1>
            <p className="max-w-3xl text-sm leading-6 text-muted-foreground">
              Real-time oversight of the Bhawana Sovereign Ledger.
            </p>
          </div>
          <Button asChild className="w-fit">
            <Link to="/loan-applications">New Loan Application</Link>
          </Button>
        </div>

        {adminLoading && !overview ? <AdminDashboardState loading error="" /> : null}
        {!adminLoading && adminError ? <AdminDashboardState loading={false} error={adminError} /> : null}
        {!adminLoading && !adminError && overview ? (
          <AdminHomeDashboard
            overview={overview}
            summary={summaryQuery.data ?? null}
            priorityAccounts={priorityAccounts}
          />
        ) : null}
      </section>
    )
  }

  return (
    <section className="space-y-6">
      <div className="space-y-2">
        <p className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">
          Workspace
        </p>
        <h1 className="font-heading text-3xl font-bold tracking-[-0.03em] text-primary lg:text-4xl">
          Operational workspace
        </h1>
        <p className="max-w-3xl text-sm leading-6 text-muted-foreground">
          Use the routed workspace links below to access the surfaces available for your current
          role set.
        </p>
      </div>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {links.map((link) => (
          <HomeLinkCard key={link.to} {...link} />
        ))}
      </div>
    </section>
  )
}
