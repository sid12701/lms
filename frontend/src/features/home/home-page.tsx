import { useEffect, useMemo, useState } from 'react'
import {
  BarChart3,
  Building2,
  CircleDollarSign,
  FileText,
  NotebookPen,
  Settings2,
  TriangleAlert,
  type LucideIcon,
  Users,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '../../components/ui/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import {
  getHomeOverview,
  type HomeOverviewLspBreakdownRecord,
  type HomeOverviewResponse,
} from '../api/lms-api'
import { useAuth } from '../auth/auth-context'

type HomeLink = {
  to: string
  title: string
  description: string
  icon: LucideIcon
}

const amountFormatter = new Intl.NumberFormat('en-IN', {
  maximumFractionDigits: 0,
})

const percentFormatter = new Intl.NumberFormat('en-IN', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

function currencyLabel(value: number) {
  return `\u20B9${amountFormatter.format(value)}`
}

function countLabel(value: number) {
  return amountFormatter.format(value)
}

function percentLabel(value: number) {
  return `${percentFormatter.format(value)}%`
}

function bucketLabel(bucket: string) {
  switch (bucket) {
    case 'CURRENT':
      return 'Current'
    case 'DPD_1_30':
      return '1-30 DPD'
    case 'DPD_31_60':
      return '31-60 DPD'
    case 'DPD_61_90':
      return '61-90 DPD'
    case 'DPD_90_PLUS':
      return '90+ DPD'
    default:
      return bucket.replace(/_/g, ' ')
  }
}

function bucketTone(bucket: string) {
  switch (bucket) {
    case 'CURRENT':
      return '#137b56'
    case 'DPD_1_30':
      return '#b48e4b'
    case 'DPD_31_60':
      return '#d27a2f'
    case 'DPD_61_90':
      return '#c05a2b'
    case 'DPD_90_PLUS':
      return '#b23a48'
    default:
      return '#5e6680'
  }
}

function barWidth(value: number, maxValue: number) {
  if (value <= 0 || maxValue <= 0) {
    return '0%'
  }

  return `${Math.max(6, Math.min(100, (value / maxValue) * 100))}%`
}

function HomeLinkCard({ to, title, description, icon: Icon }: HomeLink) {
  return (
    <Link to={to} style={{ textDecoration: 'none', color: 'inherit' }}>
      <Card className="list-card" style={{ height: '100%' }}>
        <CardHeader>
          <div className="inline-actions">
            <Icon size={18} />
            <div className="section-eyebrow">Workspace</div>
          </div>
          <CardTitle>{title}</CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
      </Card>
    </Link>
  )
}

function OverviewKpiCard({
  icon: Icon,
  label,
  value,
  detail,
  tone,
}: {
  icon: LucideIcon
  label: string
  value: string
  detail: string
  tone: {
    background: string
    color: string
  }
}) {
  return (
    <div
      style={{
        display: 'grid',
        gap: '0.75rem',
        padding: '1rem',
        borderRadius: 'var(--radius-lg)',
        background: 'var(--color-surface-alt)',
        boxShadow: 'inset 0 0 0 1px rgba(0, 6, 102, 0.06)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: '1rem' }}>
        <div style={{ display: 'grid', gap: '0.25rem' }}>
          <div className="section-eyebrow">{label}</div>
          <strong style={{ fontSize: '1.55rem', lineHeight: 1.05 }}>{value}</strong>
        </div>
        <div
          aria-hidden="true"
          style={{
            display: 'grid',
            placeItems: 'center',
            width: '2.35rem',
            height: '2.35rem',
            borderRadius: '999px',
            background: tone.background,
            color: tone.color,
            flexShrink: 0,
          }}
        >
          <Icon size={18} />
        </div>
      </div>
      <div className="helper-copy">{detail}</div>
    </div>
  )
}

function AdminHomeDashboard({ overview }: { overview: HomeOverviewResponse }) {
  const breakdown = useMemo<HomeOverviewLspBreakdownRecord[]>(() => overview.lspBreakdown, [overview.lspBreakdown])
  const maxDisbursedAmount = useMemo(
    () => breakdown.reduce((max, item) => Math.max(max, item.disbursedAmount), 0),
    [breakdown],
  )
  const maxDpdAmount = useMemo(
    () => breakdown.reduce((max, item) => Math.max(max, item.dpd90PlusAmount), 0),
    [breakdown],
  )
  const maxBucketAmount = useMemo(
    () =>
      breakdown.reduce(
        (max, item) =>
          Math.max(max, ...(item.bucketBreakdown ?? []).map((bucket) => bucket.outstandingAmount)),
        0,
      ),
    [breakdown],
  )

  return (
    <div style={{ display: 'grid', gap: '1rem' }}>
      <Card className="content-card">
        <CardHeader>
          <div className="section-eyebrow">Admin overview</div>
          <CardTitle>Portfolio health</CardTitle>
          <CardDescription>
            Concentration, delinquency, and recovery pressure across the internal portfolio surface.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
              gap: '0.9rem',
            }}
          >
            <OverviewKpiCard
              icon={CircleDollarSign}
              label="Total disbursed"
              value={currencyLabel(overview.totalDisbursedAmount)}
              detail="Disbursed principal captured across all internal LSPs."
              tone={{ background: 'rgba(33, 109, 164, 0.12)', color: '#1c5d99' }}
            />
            <OverviewKpiCard
              icon={BarChart3}
              label="Outstanding"
              value={currencyLabel(overview.totalOutstandingAmount)}
              detail="Open principal still on book across the internal portfolio."
              tone={{ background: 'rgba(92, 80, 194, 0.12)', color: '#4f46e5' }}
            />
            <OverviewKpiCard
              icon={TriangleAlert}
              label="DPD 90+ amount"
              value={currencyLabel(overview.dpd90PlusAmount)}
              detail="Balance sitting at 90 or more days past due."
              tone={{ background: 'rgba(192, 115, 0, 0.14)', color: '#b06f00' }}
            />
            <OverviewKpiCard
              icon={Users}
              label="DPD 90+ loans"
              value={countLabel(overview.dpd90PlusLoanCount)}
              detail="Loan count contributing to the 90+ delinquency bucket."
              tone={{ background: 'rgba(19, 123, 86, 0.12)', color: '#0f766e' }}
            />
          </div>
        </CardContent>
      </Card>

      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
          gap: '1rem',
        }}
      >
        <Card className="content-card">
          <CardHeader>
            <div className="section-eyebrow">Disbursed by LSP</div>
            <CardTitle>Visual concentration</CardTitle>
            <CardDescription>Each row compares disbursed balance and DPD 90+ balance for the same tenant.</CardDescription>
          </CardHeader>
          <CardContent>
            {!breakdown.length ? (
              <div className="empty-state">No LSP breakdown data is available.</div>
            ) : (
              <div style={{ display: 'grid', gap: '0.35rem' }}>
                {breakdown.map((item) => (
                  <div
                    key={item.lspId}
                    style={{
                      display: 'grid',
                      gap: '0.75rem',
                      padding: '0.9rem 0',
                      borderBottom: '1px solid rgba(0, 6, 102, 0.06)',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem', alignItems: 'flex-start' }}>
                      <div style={{ minWidth: 0 }}>
                        <strong>{item.lspName}</strong>
                        <div className="helper-copy">{item.lspCode}</div>
                      </div>
                      <div style={{ display: 'grid', justifyItems: 'end', gap: '0.15rem' }}>
                        <strong>{currencyLabel(item.disbursedAmount)}</strong>
                        <div className="helper-copy">
                          {percentLabel(item.shareOfDisbursedPercent)} of disbursed
                        </div>
                      </div>
                    </div>
                    <div style={{ display: 'grid', gap: '0.45rem' }}>
                      <div
                        style={{
                          height: '0.6rem',
                          borderRadius: '999px',
                          background: 'rgba(0, 6, 102, 0.08)',
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            width: barWidth(item.disbursedAmount, maxDisbursedAmount),
                            height: '100%',
                            borderRadius: 'inherit',
                            background: 'linear-gradient(90deg, #1c5d99, #6fa8dc)',
                          }}
                        />
                      </div>
                      <div
                        style={{
                          height: '0.6rem',
                          borderRadius: '999px',
                          background: 'rgba(0, 6, 102, 0.08)',
                          overflow: 'hidden',
                        }}
                      >
                        <div
                          style={{
                            width: barWidth(item.dpd90PlusAmount, maxDpdAmount),
                            height: '100%',
                            borderRadius: 'inherit',
                            background: 'linear-gradient(90deg, #b06f00, #f0af42)',
                          }}
                        />
                      </div>
                    </div>
                    <div className="inline-actions" style={{ justifyContent: 'space-between' }}>
                      <span className="helper-copy">Disbursed balance</span>
                      <span className="helper-copy">
                        DPD 90+ {currencyLabel(item.dpd90PlusAmount)} · {countLabel(item.dpd90PlusLoanCount)} loans
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="list-card">
          <CardHeader>
            <div className="section-eyebrow">LSP breakdown</div>
            <CardTitle>Amount and delinquency table</CardTitle>
            <CardDescription>Use this view to compare concentration, exposure, and delinquency share side by side.</CardDescription>
          </CardHeader>
          <CardContent>
            {!breakdown.length ? (
              <div className="empty-state">No LSP breakdown data is available.</div>
            ) : (
              <div className="table-grid">
                <div
                  className="table-row"
                  style={{
                    gridTemplateColumns: '1.45fr 1fr 1fr 1fr 0.8fr',
                  }}
                >
                  <span>LSP</span>
                  <span>Disbursed</span>
                  <span>Outstanding</span>
                  <span>DPD 90+</span>
                  <span>Loans</span>
                </div>
                {breakdown.map((item) => (
                  <div
                    key={item.lspId}
                    className="table-row"
                    style={{
                      gridTemplateColumns: '1.45fr 1fr 1fr 1fr 0.8fr',
                    }}
                  >
                    <div style={{ minWidth: 0 }}>
                      <strong>{item.lspName}</strong>
                      <div className="helper-copy">{item.lspCode}</div>
                    </div>
                    <div>
                      <strong>{currencyLabel(item.disbursedAmount)}</strong>
                      <div className="helper-copy">{percentLabel(item.shareOfDisbursedPercent)} share</div>
                    </div>
                    <div>
                      <strong>{currencyLabel(item.outstandingAmount)}</strong>
                      <div className="helper-copy">Open exposure</div>
                    </div>
                    <div>
                      <strong>{currencyLabel(item.dpd90PlusAmount)}</strong>
                      <div className="helper-copy">{percentLabel(item.shareOfDpd90PlusPercent)} share</div>
                    </div>
                    <div>
                      <Badge variant={item.dpd90PlusLoanCount > 0 ? 'warning' : 'success'}>
                        {countLabel(item.dpd90PlusLoanCount)}
                      </Badge>
                      <div className="helper-copy">Loans</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

      <Card className="content-card">
        <CardHeader>
          <div className="section-eyebrow">DPD buckets by LSP</div>
          <CardTitle>Delinquency distribution</CardTitle>
          <CardDescription>
            Each LSP shows loan count and outstanding amount split across the full DPD ladder.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!breakdown.length ? (
            <div className="empty-state">No LSP delinquency breakdown is available.</div>
          ) : (
            <div style={{ display: 'grid', gap: '1rem' }}>
              {breakdown.map((item) => (
                <div
                  key={`${item.lspId}-bucket-breakdown`}
                  style={{
                    display: 'grid',
                    gap: '0.9rem',
                    padding: '1rem',
                    borderRadius: 'var(--radius-lg)',
                    background: 'var(--color-surface-alt)',
                    boxShadow: 'inset 0 0 0 1px rgba(0, 6, 102, 0.06)',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      gap: '1rem',
                      alignItems: 'flex-start',
                    }}
                  >
                    <div>
                      <strong>{item.lspName}</strong>
                      <div className="helper-copy">{item.lspCode}</div>
                    </div>
                    <div className="inline-actions">
                      <Badge>{currencyLabel(item.outstandingAmount)} outstanding</Badge>
                      <Badge variant={item.dpd90PlusLoanCount > 0 ? 'warning' : 'success'}>
                        {countLabel(item.dpd90PlusLoanCount)} DPD 90+ loans
                      </Badge>
                    </div>
                  </div>

                  <div className="table-grid">
                    <div
                      className="table-row"
                      style={{ gridTemplateColumns: '1fr 0.9fr 1.2fr 1.6fr' }}
                    >
                      <span>Bucket</span>
                      <span>Loans</span>
                      <span>Outstanding</span>
                      <span>Visual</span>
                    </div>
                    {(item.bucketBreakdown ?? []).map((bucket) => (
                      <div
                        key={`${item.lspId}-${bucket.bucket}`}
                        className="table-row"
                        style={{ gridTemplateColumns: '1fr 0.9fr 1.2fr 1.6fr' }}
                      >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                          <span
                            aria-hidden="true"
                            style={{
                              width: '0.65rem',
                              height: '0.65rem',
                              borderRadius: '999px',
                              background: bucketTone(bucket.bucket),
                              flexShrink: 0,
                            }}
                          />
                          <strong>{bucketLabel(bucket.bucket)}</strong>
                        </div>
                        <div>
                          <Badge variant={bucket.loanCount > 0 ? 'default' : 'success'}>
                            {countLabel(bucket.loanCount)}
                          </Badge>
                        </div>
                        <div>
                          <strong>{currencyLabel(bucket.outstandingAmount)}</strong>
                        </div>
                        <div>
                          <div
                            style={{
                              height: '0.65rem',
                              borderRadius: '999px',
                              background: 'rgba(0, 6, 102, 0.08)',
                              overflow: 'hidden',
                            }}
                          >
                            <div
                              style={{
                                width: barWidth(bucket.outstandingAmount, maxBucketAmount),
                                height: '100%',
                                borderRadius: 'inherit',
                                background: bucketTone(bucket.bucket),
                              }}
                            />
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

export function HomePage() {
  const { user } = useAuth()
  const isLspUiUser = user?.roles.some((role) => role === 'LSP_UI_READ' || role === 'LSP_UI_WRITE') ?? false
  const isAdminUser = Boolean(user) && !isLspUiUser
  const [overview, setOverview] = useState<HomeOverviewResponse | null>(null)
  const [loadingOverview, setLoadingOverview] = useState(false)
  const [overviewError, setOverviewError] = useState('')

  useEffect(() => {
    if (!isAdminUser) {
      return
    }

    let cancelled = false

    async function loadOverview() {
      setLoadingOverview(true)
      setOverviewError('')

      try {
        const response = await getHomeOverview()
        if (!cancelled) {
          setOverview(response)
        }
      } catch (loadError) {
        if (!cancelled) {
          setOverviewError(loadError instanceof Error ? loadError.message : 'Unable to load the home overview.')
        }
      } finally {
        if (!cancelled) {
          setLoadingOverview(false)
        }
      }
    }

    void loadOverview()

    return () => {
      cancelled = true
    }
  }, [isAdminUser])

  const links: HomeLink[] = isLspUiUser
    ? [
        {
          to: '/my-loans',
          title: 'My Loans',
          description: 'Review only the loans mapped to your LSP and export tenant-scoped data.',
          icon: NotebookPen,
        },
      ]
    : [
        {
          to: '/loan-applications',
          title: 'Loan applications',
          description: 'Browse all applications across LSPs and use the core operational filters.',
          icon: NotebookPen,
        },
        {
          to: '/lsps',
          title: 'LSPs',
          description: 'Inspect tenant-level summary information, sanctioned users, and portfolio totals.',
          icon: Building2,
        },
        {
          to: '/reports',
          title: 'Reports',
          description: 'Generate and track MIS exports for internal portfolio reporting.',
          icon: FileText,
        },
        {
          to: '/products',
          title: 'Loan products',
          description: 'Manage product definitions and operational loan configuration.',
          icon: Settings2,
        },
        {
          to: '/users',
          title: 'Users',
          description: 'Administer platform users and access controls.',
          icon: Users,
        },
      ]

  if (!user) {
    return (
      <section className="page-stack">
        <Card className="content-card">
          <CardHeader>
            <div className="section-eyebrow">Home</div>
            <CardTitle>Loading session</CardTitle>
            <CardDescription>Preparing the workspace view for the current account.</CardDescription>
          </CardHeader>
          <CardContent>
            <div className="empty-state">Loading home dashboard...</div>
          </CardContent>
        </Card>
      </section>
    )
  }

  if (isAdminUser) {
    return (
      <section className="page-stack">
        <Card className="content-card">
          <CardHeader>
            <div className="section-eyebrow">Operations Home</div>
            <CardTitle>Internal portfolio dashboard</CardTitle>
            <CardDescription>
              Track exposure, delinquency, and tenant concentration across the admin surface.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {loadingOverview ? <div className="empty-state">Loading home overview...</div> : null}
            {overviewError ? <div className="empty-state">{overviewError}</div> : null}
            {!loadingOverview && !overviewError && overview ? <AdminHomeDashboard overview={overview} /> : null}
          </CardContent>
        </Card>
      </section>
    )
  }

  return (
    <section className="page-stack">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">{isLspUiUser ? 'LSP Home' : 'Operations Home'}</div>
          <CardTitle>{isLspUiUser ? 'Tenant workspace' : 'Internal workspace'}</CardTitle>
          <CardDescription>
            {isLspUiUser
              ? 'Use the shortcuts below to access your loan visibility and reporting surface.'
              : 'Use the shortcuts below to navigate the internal management platform.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
              gap: '1rem',
            }}
          >
            {links.map((link) => (
              <HomeLinkCard key={link.to} {...link} />
            ))}
          </div>
        </CardContent>
      </Card>
    </section>
  )
}
