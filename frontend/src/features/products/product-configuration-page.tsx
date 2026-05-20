import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Pencil, Plus, X } from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { BlueLoader } from '@/components/app/blue-loader'
import {
  AdminBadge,
  AdminButton,
  AdminEmptyState,
  AdminField,
  AdminFieldLabel,
  AdminInput,
  AdminSelect,
  AdminSurface,
} from '@/components/app/admin-page-ui'
import { queryKeys } from '../api/query-keys'
import type {
  AdminMetadata,
  LoanProductRecord,
  LoanProductStatus,
  LspOptionRecord,
  ProductLspMappingRecord,
} from '../api/lms-api'
import { getAdminMetadata, listLspOptions } from '../api/admin-api'
import {
  createLoanProduct,
  listLoanProducts,
  listProductLspMappings,
  saveProductLspMappings,
  updateLoanProduct,
} from '../api/products-api'
import { ApiError } from '../api/http-client'

type ProductFormState = {
  code: string
  name: string
  minPrincipal: string
  maxPrincipal: string
  interestRate: string
  processingFeeRate: string
  minTenureMonths: string
  maxTenureMonths: string
  status: LoanProductStatus | ''
}

const initialFormState: ProductFormState = {
  code: '',
  name: '',
  minPrincipal: '5000',
  maxPrincipal: '250000',
  interestRate: '18.50',
  processingFeeRate: '2.25',
  minTenureMonths: '6',
  maxTenureMonths: '24',
  status: '',
}

function isAccessError(error: unknown) {
  return error instanceof ApiError && (error.status === 401 || error.status === 403)
}

function statusVariant(status: LoanProductStatus): 'success' | 'warning' | 'destructive' {
  if (status === 'ACTIVE') {
    return 'success'
  }

  if (status === 'DRAFT') {
    return 'warning'
  }

  return 'destructive'
}

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function shortId(value: string) {
  const normalized = value.replaceAll('-', '')
  return normalized.length > 16 ? `${normalized.slice(0, 4)}-${normalized.slice(4, 8)}-${normalized.slice(8, 12)}` : value
}

function resetFormState(fallbackStatus: LoanProductStatus | '') {
  return {
    ...initialFormState,
    status: fallbackStatus,
  }
}

function buildFormState(product: LoanProductRecord, fallbackStatus: LoanProductStatus | ''): ProductFormState {
  return {
    code: product.code,
    name: product.name,
    minPrincipal: String(product.minPrincipal),
    maxPrincipal: String(product.maxPrincipal),
    interestRate: String(product.interestRate),
    processingFeeRate: String(product.processingFeeRate),
    minTenureMonths: String(product.minTenureMonths),
    maxTenureMonths: String(product.maxTenureMonths),
    status: product.status || fallbackStatus,
  }
}

export function ProductConfigurationPage() {
  const queryClient = useQueryClient()
  const [selectedProductId, setSelectedProductId] = useState('')
  const [editingProductId, setEditingProductId] = useState('')
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [formTargetLspId, setFormTargetLspId] = useState('')
  const [editLspSelectId, setEditLspSelectId] = useState('')
  const [editMappedLspIds, setEditMappedLspIds] = useState<string[]>([])
  const [form, setForm] = useState<ProductFormState>(initialFormState)
  const [submitting, setSubmitting] = useState(false)
  const [localError, setLocalError] = useState('')

  const metadataQuery = useQuery({
    queryKey: queryKeys.adminMetadata,
    queryFn: getAdminMetadata,
  })
  const productsQuery = useQuery({
    queryKey: queryKeys.loanProducts,
    queryFn: listLoanProducts,
  })
  const lspQuery = useQuery({
    queryKey: queryKeys.lspOptions,
    queryFn: listLspOptions,
  })
  const mappingsQuery = useQuery({
    queryKey: queryKeys.productLspMappings,
    queryFn: listProductLspMappings,
  })

  const metadata: AdminMetadata | null = metadataQuery.data ?? null
  const products: LoanProductRecord[] = productsQuery.data ?? []
  const lsps: LspOptionRecord[] = lspQuery.data ?? []
  const productMappings: ProductLspMappingRecord[] = mappingsQuery.data ?? []
  const loading =
    metadataQuery.isLoading || productsQuery.isLoading || lspQuery.isLoading || mappingsQuery.isLoading
  const permissionDenied =
    isAccessError(metadataQuery.error) ||
    isAccessError(productsQuery.error) ||
    isAccessError(lspQuery.error) ||
    isAccessError(mappingsQuery.error)
  const queryError = permissionDenied ? null : metadataQuery.error ?? productsQuery.error ?? lspQuery.error ?? mappingsQuery.error
  const error = localError || (queryError instanceof Error ? queryError.message : '')
  const formDisabled = permissionDenied || !metadata

  const statusOptions = useMemo(
    () => metadata?.loanProductStatuses ?? ['DRAFT', 'ACTIVE', 'INACTIVE'],
    [metadata],
  )
  const mappingByProduct = useMemo(
    () => new Map(productMappings.map((item) => [item.productId, item.lspIds])),
    [productMappings],
  )
  const lspById = useMemo(() => new Map(lsps.map((item) => [item.id, item])), [lsps])
  const selectedProduct = products.find((product) => product.id === selectedProductId) ?? null
  const editingProduct = editingProductId ? products.find((product) => product.id === editingProductId) ?? null : null
  const editorMode = editingProduct ? 'edit' : showCreateForm ? 'create' : 'detail'
  const isEditingProduct = editorMode === 'edit'
  const selectedMappedLspIds = selectedProduct ? mappingByProduct.get(selectedProduct.id) ?? [] : []
  const selectedMappedLsps = selectedMappedLspIds
    .map((lspId) => lspById.get(lspId))
    .filter((item): item is LspOptionRecord => Boolean(item))
  const editMappedLsps = editMappedLspIds
    .map((lspId) => lspById.get(lspId))
    .filter((item): item is LspOptionRecord => Boolean(item))
  const editAvailableLsps = lsps.filter((lsp) => !editMappedLspIds.includes(lsp.id))

  useEffect(() => {
    setSelectedProductId((current) => current || products[0]?.id || '')
  }, [products])

  useEffect(() => {
    setForm((current) =>
      current.status
        ? current
        : {
            ...current,
            status: (metadata?.loanProductStatuses?.[0] as LoanProductStatus | '') || 'DRAFT',
          },
    )
  }, [metadata])

  function resetCreateForm() {
    setFormTargetLspId('')
    setEditLspSelectId('')
    setEditMappedLspIds([])
    setForm(resetFormState((statusOptions[0] as LoanProductStatus | '') || 'DRAFT'))
    setLocalError('')
  }

  function openCreateForm() {
    resetCreateForm()
    setEditingProductId('')
    setLocalError('')
    setShowCreateForm(true)
  }

  function closeEditor() {
    setShowCreateForm(false)
    setEditingProductId('')
    resetCreateForm()
  }

  function startEditProduct(product: LoanProductRecord) {
    setSelectedProductId(product.id)
    setShowCreateForm(false)
    setFormTargetLspId('')
    setEditLspSelectId('')
    setEditingProductId(product.id)
    setEditMappedLspIds([...(mappingByProduct.get(product.id) ?? [])])
    setForm(buildFormState(product, (statusOptions[0] as LoanProductStatus | '') || 'DRAFT'))
    setLocalError('')
  }

  function addEditLsp(lspId: string) {
    if (!lspId) {
      return
    }

    setEditMappedLspIds((current) => (current.includes(lspId) ? current : [...current, lspId]))
    setEditLspSelectId('')
  }

  function removeEditLsp(lspId: string) {
    setEditMappedLspIds((current) => current.filter((item) => item !== lspId))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (formDisabled || !form.code.trim() || !form.name.trim()) {
      return
    }

    const nextStatus = (form.status || statusOptions[0] || 'DRAFT') as LoanProductStatus
    const payload = {
      code: form.code.trim().toUpperCase(),
      name: form.name.trim(),
      minPrincipal: Number(form.minPrincipal),
      maxPrincipal: Number(form.maxPrincipal),
      interestRate: Number(form.interestRate),
      processingFeeRate: Number(form.processingFeeRate),
      minTenureMonths: Number(form.minTenureMonths),
      maxTenureMonths: Number(form.maxTenureMonths),
      status: nextStatus,
    }

    setSubmitting(true)
    setLocalError('')

    try {
      const saved = editingProduct
        ? await updateLoanProduct(editingProduct.id, payload)
        : await createLoanProduct(payload)

      queryClient.setQueryData<LoanProductRecord[]>(queryKeys.loanProducts, (current = []) => {
        const next = [saved, ...current.filter((item) => item.id !== saved.id)]
        return next.sort((left, right) => left.code.localeCompare(right.code))
      })

      const nextMappedLspIds = editingProduct ? editMappedLspIds : formTargetLspId ? [formTargetLspId] : []

      if (editingProduct || nextMappedLspIds.length) {
        await saveProductLspMappings(saved.id, {
          lspIds: nextMappedLspIds,
        })
        queryClient.setQueryData<ProductLspMappingRecord[]>(queryKeys.productLspMappings, (current = []) => {
          const next = current.filter((item) => item.productId !== saved.id)
          if (nextMappedLspIds.length) {
            next.push({
              productId: saved.id,
              lspIds: [...nextMappedLspIds].sort(),
            })
          }
          return next.sort((left, right) => left.productId.localeCompare(right.productId))
        })
      }

      setSelectedProductId(saved.id)
      closeEditor()
    } catch (submitError) {
      const message = submitError instanceof Error ? submitError.message : 'Unable to save loan product.'
      setLocalError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="grid gap-8">
      <header className="grid gap-2">
        <h1 className="text-4xl font-semibold text-[#000666]">Loan Product Configuration</h1>
        <p className="max-w-3xl text-base leading-7 text-[#454652]">
          Select a product to inspect its configuration and mapped LSP access.
        </p>
      </header>

      {loading ? (
        <BlueLoader
          title="Loading product workspace"
          description="Refreshing product configuration and LSP mappings."
        />
      ) : null}

      <div className="grid items-start gap-8 xl:grid-cols-[minmax(340px,0.42fr)_minmax(0,1fr)]">
        <AdminSurface className="rounded-xl shadow-[0_8px_28px_rgba(0,6,102,0.055)]">
          <div className="grid gap-5 p-6">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 className="text-xl font-semibold text-[#000666]">Loan Products</h2>
                <p className="mt-1 text-sm leading-6 text-[#5e6680]">
                  Choose a product to view its configuration.
                </p>
              </div>
              <AdminBadge className="font-semibold">{products.length} products</AdminBadge>
            </div>

            {permissionDenied ? (
              <AdminEmptyState>
                Product administration requires an active internal admin session. Sign in again to load products.
              </AdminEmptyState>
            ) : null}

            {!permissionDenied ? (
              <div className="grid gap-3">
                {products.map((product) => {
                  const selected = !showCreateForm && selectedProductId === product.id
                  const mappedCount = mappingByProduct.get(product.id)?.length ?? 0

                  return (
                    <button
                      className={`grid gap-3 rounded-lg p-4 text-left transition ${
                        selected
                          ? 'bg-white shadow-[inset_4px_0_0_#000666,0_14px_28px_rgba(0,6,102,0.1)]'
                          : 'bg-[#f3f4f5] hover:bg-[#e7e8e9]'
                      }`}
                      key={product.id}
                      type="button"
                      onClick={() => {
                        setShowCreateForm(false)
                        setEditingProductId('')
                        setEditMappedLspIds([])
                        setLocalError('')
                        setSelectedProductId(product.id)
                      }}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <h3 className="truncate text-sm font-semibold text-[#000666]">{product.name}</h3>
                          <p className="mt-1 truncate text-xs font-medium uppercase text-[#767683]">{product.code}</p>
                        </div>
                        <AdminBadge className="font-semibold" variant={statusVariant(product.status)}>
                          {product.status}
                        </AdminBadge>
                      </div>
                      <div className="grid grid-cols-2 gap-3 text-xs">
                        <span className="font-medium text-[#5e6680]">{currencyLabel(product.maxPrincipal)}</span>
                        <span className="text-right font-medium text-[#5e6680]">{mappedCount} mapped LSPs</span>
                      </div>
                    </button>
                  )
                })}
                {!products.length ? <AdminEmptyState>No loan products found.</AdminEmptyState> : null}
                <AdminButton
                  className="mt-2 w-full gap-2 font-semibold"
                  disabled={formDisabled}
                  onClick={openCreateForm}
                >
                  <Plus size={16} />
                  Create loan product
                </AdminButton>
              </div>
            ) : null}
          </div>
        </AdminSurface>

        <AdminSurface className="rounded-xl shadow-[0_8px_28px_rgba(0,6,102,0.055)]">
          <div className="grid gap-6 p-8">
            {editorMode !== 'detail' ? (
              <form className="grid gap-6" onSubmit={handleSubmit}>
                <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
                  <div className="min-w-0">
                    <AdminBadge className="mb-3 font-semibold">
                      {isEditingProduct ? 'Edit product' : 'New product'}
                    </AdminBadge>
                    <h2 className="text-3xl font-semibold text-[#000666]">
                      {isEditingProduct ? 'Edit loan product' : 'Create loan product'}
                    </h2>
                    <p className="mt-2 max-w-2xl text-sm leading-6 text-[#5e6680]">
                      {isEditingProduct
                        ? 'Update product limits, pricing, tenure, status, and mapped LSP access.'
                        : 'Define the product limits, pricing, tenure, status, and optional first LSP mapping.'}
                    </p>
                  </div>
                  <AdminButton
                    className="font-semibold"
                    size="sm"
                    type="button"
                    variant="ghost"
                    onClick={closeEditor}
                  >
                    Cancel
                  </AdminButton>
                </div>

                <div className="grid gap-4 md:grid-cols-2">
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-product-name">
                      Product name
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-product-name"
                      placeholder="Salary Plus"
                      value={form.name}
                      onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                    />
                  </AdminField>

                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-product-code">
                      Product code
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-product-code"
                      placeholder="SALARY-PLUS"
                      value={form.code}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, code: event.target.value.toUpperCase() }))
                      }
                    />
                  </AdminField>
                </div>

                <div className="grid gap-4 md:grid-cols-2">
                  {!isEditingProduct ? (
                    <AdminField>
                      <AdminFieldLabel className="font-semibold" htmlFor="create-lsp-map">
                        LSP mapping
                      </AdminFieldLabel>
                      <AdminSelect
                        disabled={formDisabled || !lsps.length}
                        id="create-lsp-map"
                        value={formTargetLspId}
                        onChange={(event) => setFormTargetLspId(event.target.value)}
                      >
                        <option value="">No LSP mapped initially</option>
                        {lsps.map((lsp) => (
                          <option key={lsp.id} value={lsp.id}>
                            {lsp.name}
                          </option>
                        ))}
                      </AdminSelect>
                    </AdminField>
                  ) : null}

                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-status">
                      Status
                    </AdminFieldLabel>
                    <AdminSelect
                      disabled={formDisabled}
                      id="create-status"
                      value={form.status}
                      onChange={(event) =>
                        setForm((current) => ({
                          ...current,
                          status: event.target.value as LoanProductStatus,
                        }))
                      }
                    >
                      <option value="">Select a status</option>
                      {statusOptions.map((option) => (
                        <option key={option} value={option}>
                          {option}
                        </option>
                      ))}
                    </AdminSelect>
                  </AdminField>
                </div>

                {isEditingProduct ? (
                  <section className="grid gap-3">
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <h3 className="text-lg font-semibold text-[#000666]">Mapped LSP access</h3>
                      <AdminBadge className="font-semibold">{editMappedLsps.length} selected</AdminBadge>
                    </div>
                    {lsps.length ? (
                      <div className="grid gap-3">
                        <AdminField>
                          <AdminFieldLabel className="font-semibold" htmlFor="edit-lsp-map">
                            Add LSP mapping
                          </AdminFieldLabel>
                          <AdminSelect
                            disabled={formDisabled || !editAvailableLsps.length}
                            id="edit-lsp-map"
                            value={editLspSelectId}
                            onChange={(event) => {
                              setEditLspSelectId(event.target.value)
                              addEditLsp(event.target.value)
                            }}
                          >
                            <option value="">
                              {editAvailableLsps.length ? 'Select an LSP to add' : 'All available LSPs are mapped'}
                            </option>
                            {editAvailableLsps.map((lsp) => (
                              <option key={lsp.id} value={lsp.id}>
                                {lsp.name} ({lsp.code})
                              </option>
                            ))}
                          </AdminSelect>
                        </AdminField>

                        {editMappedLsps.length ? (
                          <div className="grid gap-2">
                            {editMappedLsps.map((lsp) => (
                              <div
                                className="flex items-center justify-between gap-3 rounded-lg bg-[#f3f4f5] p-3"
                                key={lsp.id}
                              >
                                <div className="grid min-w-0 gap-1">
                                  <span className="truncate text-sm font-semibold text-[#191c1d]">{lsp.name}</span>
                                  <span className="truncate text-xs font-medium uppercase text-[#767683]">
                                    {lsp.code}
                                  </span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <AdminBadge
                                    className="font-semibold"
                                    variant={lsp.status === 'ACTIVE' ? 'success' : 'warning'}
                                  >
                                    {lsp.status}
                                  </AdminBadge>
                                  <button
                                    aria-label={`Remove ${lsp.name}`}
                                    className="inline-flex h-8 w-8 items-center justify-center rounded-md text-[#5e6680] transition hover:bg-white hover:text-[#000666] disabled:pointer-events-none disabled:opacity-50"
                                    disabled={formDisabled}
                                    type="button"
                                    onClick={() => removeEditLsp(lsp.id)}
                                  >
                                    <X size={15} />
                                  </button>
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <AdminEmptyState>No LSPs are mapped to this loan product.</AdminEmptyState>
                        )}
                      </div>
                    ) : (
                      <AdminEmptyState>No LSPs are available for mapping.</AdminEmptyState>
                    )}
                  </section>
                ) : null}

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-min-principal">
                      Min amount
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-min-principal"
                      min="0"
                      step="0.01"
                      type="number"
                      value={form.minPrincipal}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, minPrincipal: event.target.value }))
                      }
                    />
                  </AdminField>
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-max-principal">
                      Max amount
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-max-principal"
                      min="0"
                      step="0.01"
                      type="number"
                      value={form.maxPrincipal}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, maxPrincipal: event.target.value }))
                      }
                    />
                  </AdminField>
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-interest-rate">
                      Interest %
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-interest-rate"
                      min="0"
                      step="0.01"
                      type="number"
                      value={form.interestRate}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, interestRate: event.target.value }))
                      }
                    />
                  </AdminField>
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-fee-rate">
                      Fee %
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-fee-rate"
                      min="0"
                      step="0.01"
                      type="number"
                      value={form.processingFeeRate}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, processingFeeRate: event.target.value }))
                      }
                    />
                  </AdminField>
                </div>

                <div className="grid gap-4 md:grid-cols-2">
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-min-tenure">
                      Min tenure
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-min-tenure"
                      min="1"
                      step="1"
                      type="number"
                      value={form.minTenureMonths}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, minTenureMonths: event.target.value }))
                      }
                    />
                  </AdminField>
                  <AdminField>
                    <AdminFieldLabel className="font-semibold" htmlFor="create-max-tenure">
                      Max tenure
                    </AdminFieldLabel>
                    <AdminInput
                      disabled={formDisabled}
                      id="create-max-tenure"
                      min="1"
                      step="1"
                      type="number"
                      value={form.maxTenureMonths}
                      onChange={(event) =>
                        setForm((current) => ({ ...current, maxTenureMonths: event.target.value }))
                      }
                    />
                  </AdminField>
                </div>

                {error ? <AdminEmptyState>{error}</AdminEmptyState> : null}
                {permissionDenied ? (
                  <AdminEmptyState>Sign in again before creating a loan product.</AdminEmptyState>
                ) : null}

                <div className="flex flex-wrap justify-end gap-3">
                  <AdminButton
                    className="font-semibold"
                    type="button"
                    variant="secondary"
                    onClick={closeEditor}
                  >
                    Cancel
                  </AdminButton>
                  <AdminButton className="font-semibold" disabled={formDisabled || submitting} type="submit">
                    {submitting
                      ? isEditingProduct
                        ? 'Saving...'
                        : 'Creating...'
                      : isEditingProduct
                        ? 'Save changes'
                        : 'Create product'}
                  </AdminButton>
                </div>
              </form>
            ) : !selectedProduct ? (
              <AdminEmptyState>Select a loan product to view its configuration.</AdminEmptyState>
            ) : (
              <>
                <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_auto] md:items-start">
                  <div className="min-w-0">
                    <AdminBadge className="mb-3 font-semibold" variant={statusVariant(selectedProduct.status)}>
                      {selectedProduct.status}
                    </AdminBadge>
                    <h2 className="truncate text-3xl font-semibold text-[#000666]">{selectedProduct.name}</h2>
                    <p className="mt-2 text-sm font-medium uppercase text-[#767683]">{selectedProduct.code}</p>
                  </div>
                  <div className="flex flex-wrap items-center justify-start gap-2 md:justify-end">
                    <AdminBadge className="font-semibold">{selectedMappedLsps.length} mapped LSPs</AdminBadge>
                    <AdminButton
                      className="gap-2 font-semibold"
                      disabled={formDisabled}
                      size="sm"
                      type="button"
                      variant="secondary"
                      onClick={() => startEditProduct(selectedProduct)}
                    >
                      <Pencil size={14} />
                      Edit product
                    </AdminButton>
                  </div>
                </div>

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                  <div className="rounded-lg bg-[#f3f4f5] p-4">
                    <p className="text-xs font-medium uppercase text-[#767683]">Principal range</p>
                    <p className="mt-2 text-lg font-semibold text-[#191c1d]">
                      {currencyLabel(selectedProduct.minPrincipal)} to {currencyLabel(selectedProduct.maxPrincipal)}
                    </p>
                  </div>
                  <div className="rounded-lg bg-[#f3f4f5] p-4">
                    <p className="text-xs font-medium uppercase text-[#767683]">Interest rate</p>
                    <p className="mt-2 text-lg font-semibold text-[#191c1d]">{selectedProduct.interestRate}% p.a.</p>
                  </div>
                  <div className="rounded-lg bg-[#f3f4f5] p-4">
                    <p className="text-xs font-medium uppercase text-[#767683]">Processing fee</p>
                    <p className="mt-2 text-lg font-semibold text-[#191c1d]">
                      {selectedProduct.processingFeeRate}%
                    </p>
                  </div>
                  <div className="rounded-lg bg-[#f3f4f5] p-4">
                    <p className="text-xs font-medium uppercase text-[#767683]">Tenure</p>
                    <p className="mt-2 text-lg font-semibold text-[#191c1d]">
                      {selectedProduct.minTenureMonths} to {selectedProduct.maxTenureMonths} months
                    </p>
                  </div>
                </div>

                <section className="grid gap-3">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <h3 className="text-xl font-semibold text-[#000666]">Mapped LSPs</h3>
                    <AdminBadge className="font-semibold">{selectedMappedLsps.length} active mappings</AdminBadge>
                  </div>
                  {selectedMappedLsps.length ? (
                    <div className="grid gap-3 md:grid-cols-2">
                      {selectedMappedLsps.map((lsp) => (
                        <article className="rounded-lg bg-[#f3f4f5] p-4" key={lsp.id}>
                          <div className="flex items-start justify-between gap-3">
                            <div className="min-w-0">
                              <h4 className="truncate text-base font-semibold text-[#191c1d]">{lsp.name}</h4>
                              <p className="mt-1 truncate text-xs font-mono text-[#767683]">
                                UUID: {shortId(lsp.id)}
                              </p>
                              <p className="mt-1 truncate text-xs font-medium uppercase text-[#767683]">{lsp.code}</p>
                            </div>
                            <AdminBadge className="font-semibold" variant={lsp.status === 'ACTIVE' ? 'success' : 'warning'}>
                              {lsp.status}
                            </AdminBadge>
                          </div>
                        </article>
                      ))}
                    </div>
                  ) : (
                    <AdminEmptyState>No LSPs are mapped to this loan product.</AdminEmptyState>
                  )}
                </section>
              </>
            )}
          </div>
        </AdminSurface>
      </div>
    </div>
  )
}
