import { Fragment, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { PageHeader } from '@/components/app/page-header'
import { ContentState } from '@/components/app/content-state'
import { MetricCard } from '@/components/app/metric-card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { acknowledgeOpsAlert, listOpsAlerts } from '@/features/api/alerts-api'
import type { OpsAlertRecord, OpsAlertSeverity } from '@/features/api/lms-api'

type AlertFilter = 'all' | 'critical' | 'high' | 'awaiting'
type AlertSort = 'newest' | 'severity'

function severityVariant(severity: OpsAlertSeverity) {
  switch (severity) {
    case 'CRITICAL':
      return 'destructive'
    case 'HIGH':
      return 'warning'
    case 'MEDIUM':
      return 'default'
    case 'LOW':
      return 'success'
    default:
      return 'default'
  }
}

function formatAlertTimestamp(value: string | null) {
  if (!value) {
    return 'Pending'
  }

  return new Date(value).toLocaleString('en-IN')
}

function formatContextSummary(contextJson: string | null) {
  if (!contextJson) {
    return 'No structured context attached.'
  }

  try {
    const parsed = JSON.parse(contextJson) as Record<string, unknown>
    const preview = Object.entries(parsed)
      .slice(0, 3)
      .map(([key, value]) => `${key}: ${String(value)}`)
      .join(' | ')

    return preview || 'Structured context available.'
  } catch {
    return contextJson
  }
}

export function AlertsPage() {
  const queryClient = useQueryClient()
  const [activeFilter, setActiveFilter] = useState<AlertFilter>('all')
  const [sortBy, setSortBy] = useState<AlertSort>('newest')
  const [localError, setLocalError] = useState('')
  const [expandedAlertId, setExpandedAlertId] = useState<string | null>(null)
  const [acknowledgingId, setAcknowledgingId] = useState<string | null>(null)

  const alertsQuery = useQuery({
    queryKey: ['ops-alerts'],
    queryFn: () => listOpsAlerts(),
  })

  const alerts: OpsAlertRecord[] = alertsQuery.data ?? []
  const loading = alertsQuery.isLoading
  const error = localError || (alertsQuery.error instanceof Error ? alertsQuery.error.message : '')

  const filteredAlerts = useMemo(() => {
    const visibleAlerts = alerts.filter((alert) => {
      if (activeFilter === 'all') {
        return true
      }

      if (activeFilter === 'critical') {
        return alert.severity === 'CRITICAL'
      }

      if (activeFilter === 'high') {
        return alert.severity === 'HIGH'
      }

      if (activeFilter === 'awaiting') {
        return alert.status === 'NEW'
      }

      return true
    })

    return [...visibleAlerts].sort((left, right) => {
      if (sortBy === 'severity') {
        const severityOrder: Record<OpsAlertSeverity, number> = {
          CRITICAL: 0,
          HIGH: 1,
          MEDIUM: 2,
          LOW: 3,
        }

        const severityDelta = severityOrder[left.severity] - severityOrder[right.severity]
        if (severityDelta !== 0) {
          return severityDelta
        }
      }

      return Date.parse(right.createdAt) - Date.parse(left.createdAt)
    })
  }, [activeFilter, alerts, sortBy])

  async function handleAcknowledge(id: string) {
    setAcknowledgingId(id)
    setLocalError('')

    try {
      const updatedAlert = await acknowledgeOpsAlert(id)
      queryClient.setQueryData<OpsAlertRecord[]>(['ops-alerts'], (current = []) =>
        current.map((alert) => (alert.id === updatedAlert.id ? updatedAlert : alert)),
      )
      setExpandedAlertId((current) => (current === id ? null : current))
    } catch (acknowledgeError) {
      setLocalError(
        acknowledgeError instanceof Error
          ? acknowledgeError.message
          : 'Unable to acknowledge the alert.',
      )
    } finally {
      setAcknowledgingId(null)
    }
  }

  const newCount = alerts.filter((alert) => alert.status === 'NEW').length
  const criticalCount = alerts.filter((alert) => alert.severity === 'CRITICAL').length
  const acknowledgedCount = alerts.filter((alert) => alert.status === 'ACKNOWLEDGED').length
  const latestCreatedAt = alerts[0]?.createdAt ?? null

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Operational alerts"
        description="Live alerting for internal system and servicing workflows. This surface only exposes backend-supported acknowledgment actions."
      />

      <div className="grid gap-4 xl:grid-cols-4">
        <MetricCard
          label="Active Alerts"
          value={newCount.toLocaleString('en-IN')}
          detail="Alerts currently awaiting acknowledgment."
          tone={newCount > 0 ? 'danger' : 'default'}
        />
        <MetricCard
          label="Critical Priority"
          value={criticalCount.toLocaleString('en-IN')}
          detail="Critical alerts across all internal subjects."
          tone={criticalCount > 0 ? 'danger' : 'default'}
        />
        <MetricCard
          label="Acknowledged"
          value={acknowledgedCount.toLocaleString('en-IN')}
          detail="Alerts already actioned by internal users."
          tone="success"
        />
        <MetricCard
          label="Latest Ingest"
          value={latestCreatedAt ? new Date(latestCreatedAt).toLocaleTimeString('en-IN') : '--'}
          detail={latestCreatedAt ? formatAlertTimestamp(latestCreatedAt) : 'No alerts have been ingested yet.'}
        />
      </div>

      <Card className="border-border/60">
        <CardContent className="flex flex-col gap-4 p-3 lg:flex-row lg:items-center">
          <div className="flex items-center gap-2 border-b border-border/70 pb-3 lg:border-b-0 lg:border-r lg:pb-0 lg:pr-4">
            <span className="text-sm font-semibold text-muted-foreground">Filters</span>
          </div>

          <Tabs value={activeFilter} onValueChange={(value) => setActiveFilter(value as AlertFilter)} className="min-w-0 flex-1">
            <TabsList className="h-auto flex-wrap justify-start">
              <TabsTrigger value="all">All alerts</TabsTrigger>
              <TabsTrigger value="critical">Critical</TabsTrigger>
              <TabsTrigger value="high">High</TabsTrigger>
              <TabsTrigger value="awaiting">Awaiting acknowledgment</TabsTrigger>
            </TabsList>
          </Tabs>

          <div className="flex items-center gap-2 lg:ml-auto">
            <span className="text-xs text-muted-foreground">Sort by:</span>
            <Select value={sortBy} onValueChange={(value) => setSortBy((value as AlertSort) || 'newest')}>
              <SelectTrigger className="h-8 w-[160px] text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="newest">Newest first</SelectItem>
                <SelectItem value="severity">Severity: high to low</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </CardContent>
      </Card>

      {loading ? (
        <ContentState
          title="Loading alerts"
          description="Fetching the current internal alert stream."
          tone="loading"
        />
      ) : error ? (
        <ContentState title="Unable to load alerts" description={error} tone="error" />
      ) : filteredAlerts.length === 0 ? (
        <ContentState
          title="No alerts matched the current filters"
          description="Try changing the severity or acknowledgment filters."
        />
      ) : (
        <Card>
          <Table>
            <TableHeader>
              <TableRow className="bg-muted hover:bg-muted">
                <TableHead className="px-5 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Alert
                </TableHead>
                <TableHead className="px-5 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Subject
                </TableHead>
                <TableHead className="px-5 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Message
                </TableHead>
                <TableHead className="px-5 py-3 text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Status
                </TableHead>
                <TableHead className="px-5 py-3 text-right text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Actions
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredAlerts.map((alert) => {
                const isExpanded = expandedAlertId === alert.id

                return (
                  <Fragment key={alert.id}>
                    <TableRow className={isExpanded ? 'bg-primary/3 hover:bg-primary/3' : undefined}>
                      <TableCell className="px-5 py-4">
                        <div className="flex flex-col gap-1.5">
                          <span className="text-sm font-semibold text-primary">{alert.title}</span>
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge variant={severityVariant(alert.severity)} className="w-fit text-[0.62rem]">
                              {alert.severity}
                            </Badge>
                            <span className="text-xs text-muted-foreground">{alert.type}</span>
                          </div>
                        </div>
                      </TableCell>
                      <TableCell className="px-5 py-4">
                        <div className="space-y-1 text-sm">
                          <p className="font-semibold text-foreground">{alert.subjectType}</p>
                          <p className="text-muted-foreground">{alert.subjectId || alert.correlationId || '--'}</p>
                        </div>
                      </TableCell>
                      <TableCell className="px-5 py-4 text-sm leading-6 text-foreground">
                        {alert.message}
                      </TableCell>
                      <TableCell className="px-5 py-4">
                        <Badge variant={alert.status === 'NEW' ? 'warning' : 'success'} className="text-[0.62rem]">
                          {alert.status === 'NEW' ? 'Awaiting acknowledgment' : 'Acknowledged'}
                        </Badge>
                      </TableCell>
                      <TableCell className="px-5 py-4 text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setExpandedAlertId((current) => (current === alert.id ? null : alert.id))}
                        >
                          {isExpanded ? 'Hide' : 'Review'}
                        </Button>
                      </TableCell>
                    </TableRow>

                    {isExpanded ? (
                      <ExpandedAlertRow
                        alert={alert}
                        acknowledging={acknowledgingId === alert.id}
                        onAcknowledge={() => void handleAcknowledge(alert.id)}
                      />
                    ) : null}
                  </Fragment>
                )
              })}
            </TableBody>
          </Table>
        </Card>
      )}
    </div>
  )
}

function ExpandedAlertRow({
  alert,
  acknowledging,
  onAcknowledge,
}: {
  alert: OpsAlertRecord
  acknowledging: boolean
  onAcknowledge: () => void
}) {
  return (
    <TableRow className="bg-muted/40 hover:bg-muted/40">
      <TableCell colSpan={5} className="px-5 py-5">
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="space-y-3">
            <div>
              <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                Alert summary
              </p>
              <p className="mt-2 text-sm leading-6 text-foreground">{alert.message}</p>
            </div>

            <div className="grid gap-3 sm:grid-cols-3">
              <div className="rounded-xl border border-border/70 bg-card px-4 py-3">
                <p className="text-[0.68rem] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                  Subject
                </p>
                <p className="mt-1 text-sm font-semibold text-primary">
                  {alert.subjectType}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {alert.subjectId || '--'}
                </p>
              </div>
              <div className="rounded-xl border border-border/70 bg-card px-4 py-3">
                <p className="text-[0.68rem] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                  Severity
                </p>
                <p className="mt-1 text-sm font-semibold text-foreground">{alert.severity}</p>
              </div>
              <div className="rounded-xl border border-border/70 bg-card px-4 py-3">
                <p className="text-[0.68rem] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                  Created
                </p>
                <p className="mt-1 text-sm font-semibold text-foreground">
                  {formatAlertTimestamp(alert.createdAt)}
                </p>
              </div>
            </div>

            <div className="rounded-2xl border border-border/70 bg-card p-4">
              <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                Context preview
              </p>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                {formatContextSummary(alert.contextJson)}
              </p>
            </div>
          </div>

          <div className="rounded-2xl border border-border/70 bg-card p-4">
            <div className="space-y-3">
              <div>
                <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
                  Acknowledgment state
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  {alert.acknowledgedAt
                    ? `Acknowledged by ${alert.acknowledgedByUsername || 'internal user'} on ${formatAlertTimestamp(alert.acknowledgedAt)}.`
                    : 'This alert is still awaiting acknowledgment.'}
                </p>
              </div>

              <Separator />

              <div className="space-y-2 text-sm text-muted-foreground">
                <p>Correlation ID: {alert.correlationId || '--'}</p>
                <p>Backend-backed actions only: note capture and escalation remain out of scope in this pass.</p>
              </div>

              <div className="flex flex-wrap items-center justify-end gap-2">
                <Button
                  onClick={onAcknowledge}
                  disabled={alert.status === 'ACKNOWLEDGED' || acknowledging}
                >
                  {acknowledging ? 'Acknowledging...' : 'Acknowledge alert'}
                </Button>
              </div>
            </div>
          </div>
        </div>
      </TableCell>
    </TableRow>
  )
}
