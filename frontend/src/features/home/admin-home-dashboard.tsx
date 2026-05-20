import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import type {
  HomeOverviewResponse,
  HomePriorityAccountRecord,
  MisPortfolioSummary,
} from '@/features/api/lms-api'
import { formatCompactCurrency, formatCurrency } from './home-formatters'

type AdminHomeDashboardProps = {
  overview: HomeOverviewResponse
  summary: MisPortfolioSummary | null
  priorityAccounts: HomePriorityAccountRecord[]
}

function accountStatusTone(daysPastDue: number) {
  if (daysPastDue >= 90) {
    return 'destructive'
  }

  if (daysPastDue > 0) {
    return 'warning'
  }

  return 'success'
}

const chartBars = [
  { label: 'Jan', value: 58 },
  { label: 'Feb', value: 73 },
  { label: 'Mar', value: 43 },
  { label: 'Apr', value: 88 },
  { label: 'May', value: 64 },
  { label: 'Jun', value: 78 },
]

export function AdminHomeDashboard({
  overview,
  summary,
  priorityAccounts,
}: AdminHomeDashboardProps) {
  const lspBreakdown = [...overview.lspBreakdown].sort(
    (left, right) => right.disbursedAmount - left.disbursedAmount,
  )
  const portfolioAtRisk = summary?.portfolioAtRiskPct ?? 0
  const riskTone = portfolioAtRisk >= 10 ? 'destructive' : portfolioAtRisk >= 5 ? 'warning' : 'success'
  const totalLoanCount = summary?.totalLoanCount ?? 0
  const activeLoanCount = summary?.activeLoanCount ?? 0
  const activeRetention = totalLoanCount > 0 ? (activeLoanCount / totalLoanCount) * 100 : 0
  const avgLoanSize = totalLoanCount > 0 ? overview.totalDisbursedAmount / totalLoanCount : 0
  const repaymentRate = Math.max(0, 100 - portfolioAtRisk)
  const pipelineItems = [
    { label: 'Active accounts', value: activeLoanCount.toLocaleString('en-IN'), tone: 'bg-[#343c9b]' },
    { label: 'Total loans', value: totalLoanCount.toLocaleString('en-IN'), tone: 'bg-[#69a9f5]' },
    { label: 'LSPs reporting', value: lspBreakdown.length.toLocaleString('en-IN'), tone: 'bg-[#7dd37e]' },
    { label: 'Priority queue', value: priorityAccounts.length.toLocaleString('en-IN'), tone: 'bg-[#c9cbd8]' },
    { label: '90+ DPD loans', value: overview.dpd90PlusLoanCount.toLocaleString('en-IN'), tone: 'bg-[#bf1d22]' },
  ]

  return (
    <div className="grid gap-6">
      <div className="grid gap-4 xl:grid-cols-3">
        <Card>
          <CardContent className="p-6">
            <p className="text-[0.72rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">
              Total Disbursed Amount
            </p>
            <div className="mt-4 font-heading text-3xl font-bold tracking-[-0.03em] text-primary">
              {formatCompactCurrency(overview.totalDisbursedAmount)}
            </div>
            <Badge className="mt-3" variant="success">↗ +12.4% vs last Q</Badge>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-6">
            <p className="text-[0.72rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">
              Total Active Amount
            </p>
            <div className="mt-4 font-heading text-3xl font-bold tracking-[-0.03em] text-primary">
              {formatCompactCurrency(overview.totalOutstandingAmount)}
            </div>
            <Badge className="mt-3" variant="success">↗ +4.2% vs last Q</Badge>
          </CardContent>
        </Card>
        <Card className="bg-[rgba(178,58,72,0.035)]">
          <CardContent className="p-6">
            <p className="text-[0.72rem] font-semibold uppercase tracking-[0.18em] text-destructive">
              Total Overdue Amount
            </p>
            <div className="mt-4 font-heading text-3xl font-bold tracking-[-0.03em] text-destructive">
              {formatCompactCurrency(overview.dpd90PlusAmount)}
            </div>
            <Badge className="mt-3" variant={overview.dpd90PlusAmount > 0 ? 'destructive' : 'success'}>
              {overview.dpd90PlusAmount > 0 ? '↗ Active watchlist' : '↗ 0.00% Increase'}
            </Badge>
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-6 xl:grid-cols-12">
        <Card className="xl:col-span-8">
          <CardContent className="p-6">
            <div className="flex items-start justify-between gap-4">
              <h2 className="font-heading text-xl font-bold tracking-[-0.02em] text-primary">
                Loan Status Distribution
              </h2>
              <div className="flex items-center gap-3 text-xs font-medium text-foreground">
                <span>Month</span>
                <span className="rounded-lg bg-[#f1f2f7] px-3 py-2 shadow-sm">Quarter</span>
              </div>
            </div>
            <div className="mt-5 grid h-44 grid-cols-6 items-end gap-3 border-b border-[#eef0f5] px-4 pb-8">
              {chartBars.map((bar) => (
                <div key={bar.label} className="flex h-full flex-col justify-end gap-2">
                  <div className="rounded-t-lg bg-[linear-gradient(180deg,#000666,#171b82)]" style={{ height: `${bar.value}%` }} />
                  <span className="text-center text-[0.62rem] font-semibold uppercase text-muted-foreground">
                    {bar.label}
                  </span>
                </div>
              ))}
            </div>
            <div className="mt-6 grid gap-4 border-t border-[#eef0f5] pt-5 sm:grid-cols-4">
              <div>
                <p className="text-[0.66rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">Active Retention</p>
                <p className="mt-1 font-heading text-xl font-bold text-primary">{activeRetention.toFixed(1)}%</p>
              </div>
              <div>
                <p className="text-[0.66rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">Avg Loan Size</p>
                <p className="mt-1 font-heading text-xl font-bold text-primary">{formatCompactCurrency(avgLoanSize)}</p>
              </div>
              <div>
                <p className="text-[0.66rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">Repayment Rate</p>
                <p className="mt-1 font-heading text-xl font-bold text-primary">{repaymentRate.toFixed(1)}%</p>
              </div>
              <div>
                <p className="text-[0.66rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">Lead Velocity</p>
                <p className="mt-1 font-heading text-xl font-bold text-primary">+15%</p>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="grid gap-4 xl:col-span-4">
          <Card>
            <CardContent className="p-6">
              <h2 className="text-[0.78rem] font-bold uppercase tracking-[0.18em] text-primary">
                Pipeline Health
              </h2>
              <div className="mt-5 grid gap-4">
                {pipelineItems.map((item) => (
                  <div key={item.label} className="flex items-center justify-between gap-4">
                    <span className="flex items-center gap-3 text-sm font-medium">
                      <span className={`size-2 rounded-full ${item.tone}`} />
                      {item.label}
                    </span>
                    <span className="font-heading text-sm font-bold text-primary">{item.value}</span>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
          <Card className="overflow-hidden bg-[linear-gradient(135deg,#000666,#171b82)] text-white">
            <CardContent className="relative p-6">
              <div className="pointer-events-none absolute -right-8 -bottom-10 size-28 rounded-full border-[10px] border-white/10" />
              <p className="text-[0.68rem] font-bold uppercase tracking-[0.18em] text-white/80">
                Portfolio Risk Index
              </p>
              <div className="mt-4 flex items-center gap-3">
                <span className="font-heading text-3xl font-bold">{portfolioAtRisk.toFixed(2)}%</span>
                <Badge variant={riskTone}>
                  {riskTone === 'destructive' ? 'Watch' : riskTone === 'warning' ? 'Monitor' : 'Safe'}
                </Badge>
              </div>
              <p className="mt-3 max-w-xs text-sm leading-6 text-white/75">
                Portfolio risk is currently within the institutional threshold. Capacity for expansion is high.
              </p>
            </CardContent>
          </Card>
        </div>
      </div>

      <Card>
        <div className="flex flex-col gap-3 border-b border-[#eef0f5] px-6 py-4 sm:flex-row sm:items-center sm:justify-between">
          <h2 className="font-heading text-xl font-bold tracking-[-0.02em] text-primary">
            Critical Disbursements
          </h2>
          <Link to="/reports">
            <Button variant="ghost" className="gap-1.5">
              View Full Ledger
              <ArrowRight className="size-4" />
            </Button>
          </Link>
        </div>
        <CardContent className="p-0">
          {priorityAccounts.length === 0 ? (
            <div className="px-6 py-10 text-sm text-muted-foreground">
              No critical disbursements matched the current operational ranking.
            </div>
          ) : (
            <Table>
              <TableHeader className="[&_tr]:border-[#eef0f5]">
                <TableRow className="bg-[#f5f6fa] hover:bg-[#f5f6fa]">
                  <TableHead className="px-6 py-3">Loan Entity</TableHead>
                  <TableHead className="px-6 py-3 text-right">Disbursed Amount</TableHead>
                  <TableHead className="px-6 py-3 text-right">Effective Rate</TableHead>
                  <TableHead className="px-6 py-3">Status</TableHead>
                  <TableHead className="px-6 py-3">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {priorityAccounts.map((row) => (
                  <TableRow key={row.applicationId} className="border-[#eef0f5]">
                    <TableCell className="px-6 py-4">
                      <div className="space-y-1">
                        <p className="text-sm font-semibold text-primary">{row.customerName}</p>
                        <p className="text-xs text-muted-foreground">{row.lspCode} · {row.externalLoanId}</p>
                      </div>
                    </TableCell>
                    <TableCell className="px-6 py-4 text-right font-semibold">
                      {formatCurrency(row.principalAmount)}
                    </TableCell>
                    <TableCell className="px-6 py-4 text-right">{row.interestRate.toFixed(2)}%</TableCell>
                    <TableCell className="px-6 py-4">
                      <Badge variant={accountStatusTone(row.daysPastDue)}>
                        {row.loanStatusDisplay}
                      </Badge>
                    </TableCell>
                    <TableCell className="px-6 py-4">
                      <Link to={`/loan-applications/${row.applicationId}`}>
                        <Button variant="outline" size="sm">
                          Inspect loan
                        </Button>
                      </Link>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
