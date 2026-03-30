import { BellRing, Building2, CircleAlert, Wallet } from 'lucide-react'
import { Badge } from '../../components/ui/badge'

const metrics = [
  { label: 'Portfolio under management', value: '₹842.10 Cr', delta: '+8.2% vs last month' },
  { label: 'Active borrowers', value: '14,286', delta: '+312 new this week' },
  { label: 'LSPs onboarded', value: '12', delta: '3 with write-enabled UI access' },
  { label: 'Critical alerts', value: '07', delta: '2 require action today' },
]

const alerts = [
  {
    title: 'Webhook retry threshold exceeded',
    detail: 'Apex Finance callbacks have crossed the fourth retry window.',
    tone: 'destructive' as const,
  },
  {
    title: 'KYC queue breach',
    detail: 'Northbridge batch is 38 applications above SLA.',
    tone: 'warning' as const,
  },
  {
    title: 'Mock disbursement success spike',
    detail: 'Use this to validate active-loan conversions before ICICI integration.',
    tone: 'success' as const,
  },
]

export function DashboardPage() {
  return (
    <div className="dashboard-grid">
      <section className="metric-row">
        {metrics.map((metric) => (
          <article className="metric-card" key={metric.label}>
            <div className="metric-card__label">{metric.label}</div>
            <div className="metric-card__value">{metric.value}</div>
            <div className="metric-card__delta">{metric.delta}</div>
          </article>
        ))}
      </section>

      <section className="content-grid">
        <article className="content-card">
          <div className="content-card__header">
            <div>
              <div className="section-eyebrow">Tenant readiness</div>
              <h3>Phase 2 operational posture</h3>
            </div>
            <Badge variant="success">Internal admin focus</Badge>
          </div>
          <div className="stats-list">
            <div className="stats-row">
              <div>
                <strong>Identity and access</strong>
                <p className="helper-copy">Bootstrap JWT, role bundles, and actor scope are wired.</p>
              </div>
              <Wallet size={18} />
            </div>
            <div className="stats-row">
              <div>
                <strong>Tenant administration</strong>
                <p className="helper-copy">LSP registry and user administration are the current build slice.</p>
              </div>
              <Building2 size={18} />
            </div>
            <div className="stats-row">
              <div>
                <strong>Alert visibility</strong>
                <p className="helper-copy">Ops dashboards will surface webhook and disbursement failures next.</p>
              </div>
              <BellRing size={18} />
            </div>
          </div>
        </article>

        <aside className="list-card">
          <div className="list-card__header">
            <div>
              <div className="section-eyebrow">Alerts</div>
              <h3>Operations watchlist</h3>
            </div>
            <Badge variant="warning">
              <CircleAlert size={14} />
              7 open
            </Badge>
          </div>
          <div className="alert-list">
            {alerts.map((alert) => (
              <div className="alert-row" key={alert.title}>
                <Badge variant={alert.tone}>{alert.tone}</Badge>
                <div>
                  <strong>{alert.title}</strong>
                  <p className="helper-copy">{alert.detail}</p>
                </div>
                <span className="helper-copy">Review</span>
              </div>
            ))}
          </div>
        </aside>
      </section>
    </div>
  )
}
