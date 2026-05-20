import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { BlueLoader } from '@/components/app/blue-loader'
import {
  AdminBadge,
  AdminButton,
  AdminContent,
  AdminDescription,
  AdminEmptyState,
  AdminEyebrow,
  AdminField,
  AdminFieldLabel,
  AdminHeader,
  AdminInput,
  AdminSelect,
  AdminSurface,
  AdminTitle,
} from '@/components/app/admin-page-ui'
import { cn } from '@/lib/utils'
import { queryKeys } from '../api/query-keys'
import { ApiError } from '../api/http-client'
import type {
  AdminMetadata,
  LspDetailRecord,
  LspPortfolioSummaryRecord,
  LspRecord,
  LspStatus,
  WebhookEventType,
} from '../api/lms-api'
import { webhookEventTypeOptions } from '../api/lms-api'
import {
  createLsp,
  getAdminMetadata,
  getLspDetail,
  listLsps,
  updateLspWebhookSubscription,
} from '../api/admin-api'

function statusVariant(status: LspStatus): 'success' | 'warning' {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function isAccessError(error: unknown) {
  return error instanceof ApiError && (error.status === 401 || error.status === 403)
}

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatDateLabel(value?: string | null) {
  if (!value) {
    return 'No disbursal yet'
  }

  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium' }).format(new Date(value))
}

function webhookEventTypeLabel(eventType: WebhookEventType) {
  switch (eventType) {
    case 'LOAN_CREATED':
      return 'Loan created'
    case 'LOAN_STATUS_CHANGED':
      return 'Loan status changed'
    case 'LOAN_DISBURSEMENT_UPDATED':
      return 'Disbursement updated'
    case 'LOAN_REPAYMENT_RECORDED':
      return 'Repayment recorded'
    case 'LOAN_FORECLOSURE_COMPLETED':
      return 'Foreclosure completed'
  }
}

function renderSummaryBadges(summary: LspPortfolioSummaryRecord) {
  return (
    <div className="flex flex-wrap gap-2 md:justify-end">
      <AdminBadge>{summary.loanApplicationCount} loans</AdminBadge>
      <AdminBadge variant="success">{summary.disbursedLoanCount} disbursed</AdminBadge>
      <AdminBadge variant="warning">{currencyLabel(summary.totalDisbursedAmount)}</AdminBadge>
    </div>
  )
}

export function LspAdminPage() {
  const queryClient = useQueryClient()
  const [selectedLspId, setSelectedLspId] = useState('')
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [status, setStatus] = useState<LspStatus | ''>('')
  const [webhookEnabled, setWebhookEnabled] = useState(false)
  const [webhookEndpointUrl, setWebhookEndpointUrl] = useState('')
  const [webhookSigningSecret, setWebhookSigningSecret] = useState('')
  const [webhookEventTypes, setWebhookEventTypes] = useState<WebhookEventType[]>([])
  const [submitting, setSubmitting] = useState(false)
  const [webhookSaving, setWebhookSaving] = useState(false)
  const [localError, setLocalError] = useState('')
  const [webhookError, setWebhookError] = useState('')
  const [webhookSuccess, setWebhookSuccess] = useState('')

  const metadataQuery = useQuery({
    queryKey: queryKeys.adminMetadata,
    queryFn: getAdminMetadata,
  })
  const lspQuery = useQuery({
    queryKey: queryKeys.lspDirectory,
    queryFn: listLsps,
  })
  const detailQuery = useQuery({
    queryKey: ['admin', 'lsp-detail', selectedLspId],
    queryFn: () => getLspDetail(selectedLspId),
    enabled: Boolean(selectedLspId),
  })

  const metadata: AdminMetadata | null = metadataQuery.data ?? null
  const lsps: LspRecord[] = lspQuery.data ?? []
  const selectedLsp: LspDetailRecord | null = detailQuery.data ?? null
  const loading = metadataQuery.isLoading || lspQuery.isLoading
  const detailLoading = detailQuery.isFetching
  const permissionDenied = isAccessError(metadataQuery.error) || isAccessError(lspQuery.error)
  const queryError = permissionDenied ? null : metadataQuery.error ?? lspQuery.error
  const error = localError || (queryError instanceof Error ? queryError.message : '')
  const detailPermissionDenied = isAccessError(detailQuery.error)
  const detailError = !detailPermissionDenied && detailQuery.error instanceof Error ? detailQuery.error.message : ''
  const formDisabled = permissionDenied || !metadata

  const selectedListRecord = useMemo(
    () => lsps.find((item) => item.id === selectedLspId) ?? null,
    [lsps, selectedLspId],
  )

  useEffect(() => {
    setStatus((current) => current || (metadata?.lspStatuses[0] as LspStatus | '') || '')
  }, [metadata])

  useEffect(() => {
    setSelectedLspId((current) => current || lsps[0]?.id || '')
  }, [lsps])

  useEffect(() => {
    const subscription = selectedLsp?.webhookSubscription ?? selectedListRecord?.webhookSubscription ?? null
    setWebhookEnabled(subscription?.enabled ?? false)
    setWebhookEndpointUrl(subscription?.endpointUrl ?? '')
    setWebhookSigningSecret(subscription?.signingSecret ?? '')
    setWebhookEventTypes(subscription?.eventTypes ?? [])
    setWebhookError('')
    setWebhookSuccess('')
  }, [selectedLsp, selectedListRecord])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (formDisabled || !code.trim() || !name.trim()) {
      return
    }

    const nextStatus = status || (metadata?.lspStatuses[0] as LspStatus | undefined) || 'ACTIVE'

    setSubmitting(true)
    setLocalError('')

    try {
      const created = await createLsp({
        code,
        name,
        status: nextStatus,
      })

      queryClient.setQueryData<LspRecord[]>(queryKeys.lspDirectory, (current = []) => [
        created,
        ...current.filter((item) => item.id !== created.id),
      ])
      void queryClient.invalidateQueries({ queryKey: queryKeys.lspOptions })
      setCode('')
      setName('')
      setStatus(nextStatus)
      setSelectedLspId(created.id)
    } catch (createError) {
      setLocalError(createError instanceof Error ? createError.message : 'Unable to create LSP.')
    } finally {
      setSubmitting(false)
    }
  }

  function toggleWebhookEventType(eventType: WebhookEventType) {
    setWebhookEventTypes((current) =>
      current.includes(eventType) ? current.filter((item) => item !== eventType) : [...current, eventType],
    )
  }

  async function handleWebhookSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedLspId) {
      return
    }

    const trimmedEndpoint = webhookEndpointUrl.trim()
    const trimmedSecret = webhookSigningSecret.trim()

    if (webhookEnabled && !trimmedEndpoint) {
      setWebhookError('Endpoint URL is required when the webhook subscription is enabled.')
      return
    }

    if (webhookEnabled) {
      try {
        new URL(trimmedEndpoint)
      } catch {
        setWebhookError('Endpoint URL must be a valid absolute URL.')
        return
      }
    }

    if (webhookEnabled && !trimmedSecret) {
      setWebhookError('Signing secret is required when the webhook subscription is enabled.')
      return
    }

    if (webhookEnabled && !webhookEventTypes.length) {
      setWebhookError('Select at least one webhook event type.')
      return
    }

    setWebhookSaving(true)
    setWebhookError('')
    setWebhookSuccess('')

    try {
      await updateLspWebhookSubscription(selectedLspId, {
        enabled: webhookEnabled,
        endpointUrl: webhookEnabled ? trimmedEndpoint : undefined,
        signingSecret: webhookEnabled ? trimmedSecret : undefined,
        eventTypes: webhookEventTypes,
      })

      const [lspList, detail] = await Promise.all([listLsps(), getLspDetail(selectedLspId)])
      queryClient.setQueryData<LspRecord[]>(queryKeys.lspDirectory, lspList)
      queryClient.setQueryData<LspDetailRecord>(['admin', 'lsp-detail', selectedLspId], detail)
      void queryClient.invalidateQueries({ queryKey: queryKeys.lspOptions })
      setWebhookSuccess('Webhook subscription saved.')
    } catch (saveError) {
      setWebhookError(saveError instanceof Error ? saveError.message : 'Unable to save webhook settings.')
    } finally {
      setWebhookSaving(false)
    }
  }

  return (
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(320px,0.8fr)_minmax(0,1fr)] 2xl:grid-cols-[minmax(320px,0.62fr)_minmax(0,1fr)_minmax(320px,0.52fr)]">
      <AdminSurface>
        <AdminHeader>
          <AdminEyebrow>LSPs</AdminEyebrow>
          <AdminTitle>Tenant registry</AdminTitle>
          <AdminDescription>
            Review tenant status, assigned users, loan count, and disbursal summary.
          </AdminDescription>
        </AdminHeader>
        <AdminContent>
          <div className="flex flex-wrap items-center gap-2">
            <AdminBadge>{lsps.length} tenants</AdminBadge>
            <AdminBadge variant="warning">{metadata?.lspStatuses.length ?? 0} statuses</AdminBadge>
          </div>
          {loading ? (
            <BlueLoader
              title="Loading tenant registry"
              description="Fetching LSPs, status metadata, and portfolio summaries."
              compact
            />
          ) : null}
          {error ? <AdminEmptyState>{error}</AdminEmptyState> : null}
          {permissionDenied ? (
            <AdminEmptyState>
              LSP administration requires an active internal admin session. Sign in again to load tenant data.
            </AdminEmptyState>
          ) : null}
          {!loading && !permissionDenied && !error ? (
            <div className="grid gap-3">
              {lsps.map((lsp) => {
                const selected = selectedLspId === lsp.id

                return (
                  <button
                    className={cn(
                      'grid w-full gap-3 rounded-lg bg-[#f8f9fa] p-4 text-left shadow-[0_8px_24px_rgba(0,6,102,0.045)] transition duration-200 hover:-translate-y-0.5 hover:bg-white hover:shadow-[0_16px_34px_rgba(0,6,102,0.08)]',
                      selected && 'bg-white shadow-[inset_4px_0_0_#000666,0_18px_34px_rgba(0,6,102,0.1)]',
                    )}
                    key={lsp.id}
                    type="button"
                    onClick={() => setSelectedLspId(lsp.id)}
                  >
                    <div className="grid min-w-0 gap-3 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
                      <div className="min-w-0">
                        <strong className="block truncate text-base font-extrabold text-[#0f1729]">
                          {lsp.name}
                        </strong>
                        <p className="mt-1 truncate text-xs font-bold uppercase text-[#5e6680]">
                          {lsp.code}
                        </p>
                        <p className="mt-2 text-xs font-semibold text-[#8a92a8]">
                          {formatDateLabel(lsp.portfolioSummary.latestDisbursalDate)}
                        </p>
                      </div>
                      <AdminBadge className="justify-self-start md:justify-self-end" variant={statusVariant(lsp.status)}>
                        {lsp.status}
                      </AdminBadge>
                    </div>
                    {renderSummaryBadges(lsp.portfolioSummary)}
                  </button>
                )
              })}
              {!lsps.length ? <AdminEmptyState>No LSPs found.</AdminEmptyState> : null}
            </div>
          ) : null}
        </AdminContent>
      </AdminSurface>

      <AdminSurface>
        <AdminHeader>
          <AdminEyebrow>Selected LSP</AdminEyebrow>
          <AdminTitle>{selectedLsp?.name ?? selectedListRecord?.name ?? 'Choose a tenant'}</AdminTitle>
          <AdminDescription>Inspect sanctioned users, portfolio totals, and webhook delivery.</AdminDescription>
        </AdminHeader>
        <AdminContent>
          {!selectedLspId ? <AdminEmptyState>Select an LSP to inspect its details.</AdminEmptyState> : null}
          {detailLoading ? (
            <BlueLoader
              title="Loading tenant details"
              description="Preparing users, webhook state, and portfolio totals."
              compact
            />
          ) : null}
          {detailError ? <AdminEmptyState>{detailError}</AdminEmptyState> : null}
          {detailPermissionDenied ? (
            <AdminEmptyState>Sign in again to inspect this tenant.</AdminEmptyState>
          ) : null}
          {selectedLsp && !detailLoading ? (
            <>
              <div className="grid gap-3 rounded-lg bg-[#eef1f8]/70 p-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-center">
                <div className="min-w-0">
                  <strong className="block truncate text-lg font-extrabold text-[#0f1729]">
                    {selectedLsp.code}
                  </strong>
                  <p className="mt-1 text-sm font-semibold text-[#5e6680]">{selectedLsp.status}</p>
                </div>
                <div className="flex flex-wrap gap-2 md:justify-end">
                  <AdminBadge>{selectedLsp.userCount} users</AdminBadge>
                  <AdminBadge variant={selectedLsp.webhookSubscription?.enabled ? 'success' : 'warning'}>
                    {selectedLsp.webhookSubscription?.enabled ? 'Webhook on' : 'Webhook off'}
                  </AdminBadge>
                </div>
              </div>

              <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {[
                  ['Loans captured', selectedLsp.portfolioSummary.loanApplicationCount],
                  ['Approved', selectedLsp.portfolioSummary.approvedLoanCount],
                  ['Disbursed', selectedLsp.portfolioSummary.disbursedLoanCount],
                  ['Amount disbursed', currencyLabel(selectedLsp.portfolioSummary.totalDisbursedAmount)],
                ].map(([label, value]) => (
                  <div
                    className="rounded-lg bg-[#f8f9fa] p-4 shadow-[0_8px_22px_rgba(0,6,102,0.04)]"
                    key={label}
                  >
                    <strong className="block truncate text-xl font-extrabold text-[#0f1729]">{value}</strong>
                    <p className="mt-1 text-xs font-bold uppercase text-[#8a92a8]">{label}</p>
                  </div>
                ))}
              </div>

              <div className="grid gap-3">
                <AdminFieldLabel>Users sanctioned in the system</AdminFieldLabel>
                {!selectedLsp.users.length ? (
                  <AdminEmptyState>No users are assigned to this LSP yet.</AdminEmptyState>
                ) : (
                  <div className="grid gap-2">
                    {selectedLsp.users.map((user) => (
                      <article
                        className="grid gap-3 rounded-lg bg-[#f8f9fa] px-3 py-3 shadow-[0_6px_18px_rgba(0,6,102,0.04)] sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:items-center"
                        key={user.id}
                      >
                        <div className="min-w-0">
                          <strong className="block truncate text-sm font-extrabold text-[#0f1729]">
                            {user.username}
                          </strong>
                          <p className="truncate text-xs font-semibold text-[#5e6680]">{user.email ?? 'No email'}</p>
                        </div>
                        <AdminBadge>{user.roles.join(', ')}</AdminBadge>
                        <span className="text-xs font-bold uppercase text-[#8a92a8]">
                          {user.status}
                        </span>
                      </article>
                    ))}
                  </div>
                )}
              </div>

              <form className="grid gap-4 sm:grid-cols-2" onSubmit={handleWebhookSave}>
                <AdminField>
                  <AdminFieldLabel htmlFor="webhook-enabled">Webhook delivery</AdminFieldLabel>
                  <AdminSelect
                    id="webhook-enabled"
                    value={webhookEnabled ? 'enabled' : 'disabled'}
                    onChange={(event) => setWebhookEnabled(event.target.value === 'enabled')}
                  >
                    <option value="disabled">Disabled</option>
                    <option value="enabled">Enabled</option>
                  </AdminSelect>
                </AdminField>
                <AdminField>
                  <AdminFieldLabel htmlFor="webhook-endpoint">Endpoint URL</AdminFieldLabel>
                  <AdminInput
                    id="webhook-endpoint"
                    placeholder="https://hooks.example.com/lms"
                    value={webhookEndpointUrl}
                    onChange={(event) => setWebhookEndpointUrl(event.target.value)}
                  />
                </AdminField>
                <AdminField className="sm:col-span-2">
                  <AdminFieldLabel htmlFor="webhook-secret">Signing secret</AdminFieldLabel>
                  <AdminInput
                    id="webhook-secret"
                    placeholder="Shared signing secret"
                    value={webhookSigningSecret}
                    onChange={(event) => setWebhookSigningSecret(event.target.value)}
                  />
                </AdminField>
                <AdminField className="sm:col-span-2">
                  <AdminFieldLabel>Event types</AdminFieldLabel>
                  <div className="grid gap-2">
                    {webhookEventTypeOptions.map((eventType) => (
                      <label
                        className="grid cursor-pointer grid-cols-[auto_minmax(0,1fr)] items-center gap-3 rounded-lg bg-[#f8f9fa] px-3 py-3 shadow-[0_6px_18px_rgba(0,6,102,0.04)] transition hover:bg-white"
                        key={eventType}
                      >
                        <input
                          checked={webhookEventTypes.includes(eventType)}
                          className="size-4 accent-[#000666]"
                          type="checkbox"
                          onChange={() => toggleWebhookEventType(eventType)}
                        />
                        <span className="min-w-0">
                          <strong className="block truncate text-sm font-extrabold text-[#0f1729]">
                            {webhookEventTypeLabel(eventType)}
                          </strong>
                          <span className="block truncate text-xs font-bold uppercase text-[#5e6680]">
                            {eventType}
                          </span>
                        </span>
                      </label>
                    ))}
                  </div>
                </AdminField>
                {webhookError ? <AdminEmptyState className="sm:col-span-2">{webhookError}</AdminEmptyState> : null}
                {webhookSuccess ? (
                  <AdminEmptyState className="bg-[#dceee7] text-[#167a54] sm:col-span-2">
                    {webhookSuccess}
                  </AdminEmptyState>
                ) : null}
                <AdminButton className="sm:col-span-2" disabled={webhookSaving} type="submit">
                  {webhookSaving ? 'Saving...' : 'Save webhook subscription'}
                </AdminButton>
              </form>
            </>
          ) : null}
        </AdminContent>
      </AdminSurface>

      <AdminSurface className="xl:col-span-2 2xl:col-span-1">
        <AdminHeader>
          <AdminEyebrow>Create LSP</AdminEyebrow>
          <AdminTitle>Add tenant</AdminTitle>
          <AdminDescription>Register a tenant before assigning users, products, or API access.</AdminDescription>
        </AdminHeader>
        <AdminContent>
          <form className="grid gap-4" onSubmit={handleCreate}>
            <AdminField>
              <AdminFieldLabel htmlFor="code">Tenant code</AdminFieldLabel>
              <AdminInput
                disabled={formDisabled}
                id="code"
                value={code}
                onChange={(event) => setCode(event.target.value.toUpperCase())}
              />
            </AdminField>
            <AdminField>
              <AdminFieldLabel htmlFor="name">Tenant name</AdminFieldLabel>
              <AdminInput
                disabled={formDisabled}
                id="name"
                value={name}
                onChange={(event) => setName(event.target.value)}
              />
            </AdminField>
            <AdminField>
              <AdminFieldLabel htmlFor="status">Status</AdminFieldLabel>
              <AdminSelect
                disabled={formDisabled || !metadata?.lspStatuses.length}
                id="status"
                value={status}
                onChange={(event) => setStatus(event.target.value as LspStatus)}
              >
                <option value="">Select a status</option>
                {(metadata?.lspStatuses ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </AdminSelect>
            </AdminField>
            <AdminButton disabled={formDisabled || submitting} type="submit">
              {submitting ? 'Creating...' : 'Create tenant'}
            </AdminButton>
          </form>
        </AdminContent>
      </AdminSurface>
    </div>
  )
}
