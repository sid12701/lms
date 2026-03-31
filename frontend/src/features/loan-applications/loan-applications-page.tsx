import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { ArrowRight, CheckCircle2, FileText } from 'lucide-react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import { useAuth } from '../auth/auth-context'
import {
  ApiError,
  assignLoanApplication,
  createLoanApplication,
  getLoanApplication,
  listLoanApplicationAssignmentEvents,
  listLoanApplicationIntakeAudits,
  listLoanApplicationDocumentPlaceholders,
  listLoanApplicationStatusTransitions,
  listLoanApplications,
  listLoanProducts,
  listLsps,
  loanApplicationStatusOptions,
  loanApplicationDocumentPlaceholderStatusOptions,
  transitionLoanApplicationStatus,
  updateLoanApplicationDocumentPlaceholder,
  type LoanApplicationIntakeAuditRecord,
  type LoanApplicationAssignmentEventRecord,
  type LoanApplicationDocumentPlaceholderRecord,
  type LoanApplicationDocumentPlaceholderStatus,
  type LoanApplicationRecord,
  type LoanApplicationStatus,
  type LoanApplicationStatusTransitionRecord,
  type LoanProductRecord,
  type LspRecord,
} from '../api/lms-api'

type IntakeFormState = {
  lspId: string
  productId: string
  externalLoanId: string
  sourceChannel: string
  borrowerPan: string
  borrowerFullName: string
  borrowerMobile: string
  borrowerEmail: string
  borrowerDateOfBirth: string
  borrowerCity: string
  borrowerState: string
  borrowerEmploymentType: string
  borrowerMonthlyIncome: string
  requestedAmount: string
  tenureMonths: string
}

type ListFilterState = {
  lspId: string
  productId: string
  status: string
  sourceChannel: string
  query: string
}

type TransitionAction = {
  targetStatus: LoanApplicationStatus
  label: string
  description: string
  variant: 'primary' | 'secondary' | 'outline'
}

const initialFormState: IntakeFormState = {
  lspId: '',
  productId: '',
  externalLoanId: '',
  sourceChannel: 'PARTNER_PORTAL',
  borrowerPan: '',
  borrowerFullName: '',
  borrowerMobile: '',
  borrowerEmail: '',
  borrowerDateOfBirth: '',
  borrowerCity: '',
  borrowerState: '',
  borrowerEmploymentType: '',
  borrowerMonthlyIncome: '',
  requestedAmount: '50000',
  tenureMonths: '12',
}

const borrowerEmploymentTypeOptions = [
  'SALARIED',
  'SELF_EMPLOYED',
  'BUSINESS',
  'STUDENT',
  'RETIRED',
  'HOMEMAKER',
  'OTHER',
] as const

const initialFilterState: ListFilterState = {
  lspId: '',
  productId: '',
  status: '',
  sourceChannel: '',
  query: '',
}

const workflowProgression: Array<{
  status: LoanApplicationStatus | 'DECISION'
  label: string
  description: string
}> = [
  { status: 'RECEIVED', label: 'Received', description: 'Captured from intake and ready for review.' },
  {
    status: 'UNDER_REVIEW',
    label: 'Under review',
    description: 'Ops is validating the case and supporting data.',
  },
  {
    status: 'DECISION',
    label: 'Decision',
    description: 'Approved or rejected after review.',
  },
]

function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function formatDateLabel(value?: string | null) {
  if (!value) {
    return 'Not provided'
  }

  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
  }).format(parsed)
}

function formatAgeLabel(value?: string | null) {
  if (!value) {
    return 'Age not provided'
  }

  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return 'Age not available'
  }

  const today = new Date()
  let age = today.getFullYear() - parsed.getFullYear()
  const monthDelta = today.getMonth() - parsed.getMonth()
  if (monthDelta < 0 || (monthDelta === 0 && today.getDate() < parsed.getDate())) {
    age -= 1
  }

  return `${age} years old`
}

function formatIncomeLabel(value?: number | null) {
  if (value == null) {
    return 'Not provided'
  }

  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

function formatEmploymentType(value?: string | null) {
  if (!value) {
    return 'Not provided'
  }

  return value
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

function countBorrowerProfileSignals(application: LoanApplicationRecord | null) {
  if (!application) {
    return 0
  }

  return [
    application.borrowerDateOfBirth,
    application.borrowerCity,
    application.borrowerState,
    application.borrowerEmploymentType,
    application.borrowerMonthlyIncome,
  ].filter((value) => value != null && value !== '').length
}

function formatIncomeCoverage(application: LoanApplicationRecord | null) {
  if (!application || !application.borrowerMonthlyIncome || application.borrowerMonthlyIncome <= 0) {
    return 'Income context not provided'
  }

  const ratio = application.requestedAmount / application.borrowerMonthlyIncome
  return `${ratio.toFixed(1)}x monthly income`
}

function loanStatusLabel(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 'Received'
    case 'UNDER_REVIEW':
      return 'Under review'
    case 'APPROVED':
      return 'Approved'
    case 'REJECTED':
      return 'Rejected'
  }
}

function loanStatusVariant(
  status: LoanApplicationStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'RECEIVED':
      return 'warning'
    case 'UNDER_REVIEW':
      return 'default'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'destructive'
  }
}

function statusProgressIndex(status: LoanApplicationStatus) {
  switch (status) {
    case 'RECEIVED':
      return 1
    case 'UNDER_REVIEW':
      return 2
    case 'APPROVED':
    case 'REJECTED':
      return 3
  }
}

function getTransitionActions(status: LoanApplicationStatus): TransitionAction[] {
  switch (status) {
    case 'RECEIVED':
      return [
        {
          targetStatus: 'UNDER_REVIEW',
          label: 'Move to review',
          description: 'Promote the case into the review queue.',
          variant: 'secondary',
        },
      ]
    case 'UNDER_REVIEW':
      return [
        {
          targetStatus: 'APPROVED',
          label: 'Approve',
          description: 'Mark the case as approved and ready for downstream processing.',
          variant: 'primary',
        },
        {
          targetStatus: 'REJECTED',
          label: 'Reject',
          description: 'Close the case and capture the decision note.',
          variant: 'outline',
        },
      ]
    case 'APPROVED':
    case 'REJECTED':
      return []
  }
}

function sortByCreatedAtDesc<T extends { createdAt: string }>(records: T[]) {
  return [...records].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
}

function formatPayloadJson(payloadJson: string) {
  try {
    return JSON.stringify(JSON.parse(payloadJson), null, 2)
  } catch {
    return payloadJson
  }
}

function formatNote(note: string | null) {
  const trimmed = note?.trim()
  return trimmed ? trimmed : 'No note recorded.'
}

function formatMetadataValue(value?: string | null) {
  const trimmed = value?.trim()
  return trimmed ? trimmed : 'Not provided'
}

function formatOptionalTimestamp(value?: string | null) {
  if (!value) {
    return 'Not uploaded'
  }

  return formatTimestamp(value)
}

type ApprovalBlocker = {
  message: string
  documents: string[]
}

function extractApprovalBlocker(error: unknown): ApprovalBlocker | null {
  if (!(error instanceof ApiError)) {
    return null
  }

  const parsedBody = (() => {
    try {
      return JSON.parse(error.body) as Record<string, unknown>
    } catch {
      return null
    }
  })()

  const code =
    (parsedBody && typeof parsedBody.code === 'string' ? parsedBody.code : null) ?? error.code
  const message =
    (parsedBody && typeof parsedBody.message === 'string' ? parsedBody.message : null) ??
    error.message

  const documentCandidates = [
    parsedBody?.blockingDocuments,
    parsedBody?.blockedDocuments,
    parsedBody?.requiredDocuments,
    parsedBody?.violations,
  ]

  const documents = documentCandidates.flatMap((candidate) => {
    if (!Array.isArray(candidate)) {
      return []
    }

    return candidate.flatMap((entry) => {
      if (typeof entry === 'string') {
        return [entry]
      }

      if (entry && typeof entry === 'object') {
        const record = entry as Record<string, unknown>
        const displayName =
          typeof record.documentDisplayName === 'string'
            ? record.documentDisplayName
            : typeof record.documentType === 'string'
              ? record.documentType
              : typeof record.name === 'string'
                ? record.name
                : null
        const detail =
          typeof record.message === 'string'
            ? record.message
            : typeof record.detail === 'string'
              ? record.detail
              : null

        return displayName ? [detail ? `${displayName}: ${detail}` : displayName] : []
      }

      return []
    })
  })

  const isKycRelated =
    Boolean(code && /KYC|DOCUMENT|CHECKLIST/i.test(code)) ||
    /KYC|document|checklist|required/i.test(message) ||
    documents.length > 0

  if (!isKycRelated) {
    return null
  }

  return {
    message,
    documents: Array.from(new Set(documents)).filter(Boolean),
  }
}

function formatBlockingDocuments(documents: LoanApplicationDocumentPlaceholderRecord[]) {
  return documents.map((document) => document.documentDisplayName)
}

function loanDocumentPlaceholderStatusLabel(status: LoanApplicationDocumentPlaceholderStatus) {
  switch (status) {
    case 'PENDING':
      return 'Pending'
    case 'RECEIVED':
      return 'Received'
    case 'VERIFIED':
      return 'Verified'
    case 'REJECTED':
      return 'Rejected'
    case 'NOT_REQUIRED':
      return 'Not required'
  }
}

function loanDocumentPlaceholderStatusVariant(
  status: LoanApplicationDocumentPlaceholderStatus,
): 'default' | 'success' | 'warning' | 'destructive' {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'RECEIVED':
      return 'default'
    case 'VERIFIED':
      return 'success'
    case 'REJECTED':
      return 'destructive'
    case 'NOT_REQUIRED':
      return 'default'
  }
}

function sortLoanDocumentPlaceholders(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return [...records].sort((left, right) => {
    if (left.required !== right.required) {
      return left.required ? -1 : 1
    }

    return left.documentDisplayName.localeCompare(right.documentDisplayName)
  })
}

function seedDocumentDrafts(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return records.reduce<
    Record<
      string,
      {
        status: LoanApplicationDocumentPlaceholderStatus
        note: string
        fileName: string
        contentType: string
        sourceReference: string
      }
    >
  >((accumulator, record) => {
    accumulator[record.id] = {
      status: record.status,
      note: record.note ?? '',
      fileName: record.fileName ?? '',
      contentType: record.contentType ?? '',
      sourceReference: record.sourceReference ?? '',
    }
    return accumulator
  }, {})
}

function countDocumentMetadataSignals(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return records.filter((item) =>
    Boolean(
      item.fileName?.trim() ||
        item.contentType?.trim() ||
        item.sourceReference?.trim() ||
        item.uploadedAt ||
        item.uploadedByUsername?.trim(),
    ),
  ).length
}

export function LoanApplicationsPage() {
  const { user } = useAuth()
  const [applications, setApplications] = useState<LoanApplicationRecord[]>([])
  const [selectedLoan, setSelectedLoan] = useState<LoanApplicationRecord | null>(null)
  const [assignmentHistory, setAssignmentHistory] = useState<LoanApplicationAssignmentEventRecord[]>([])
  const [statusHistory, setStatusHistory] = useState<LoanApplicationStatusTransitionRecord[]>([])
  const [intakeAudits, setIntakeAudits] = useState<LoanApplicationIntakeAuditRecord[]>([])
  const [lsps, setLsps] = useState<LspRecord[]>([])
  const [products, setProducts] = useState<LoanProductRecord[]>([])
  const [form, setForm] = useState<IntakeFormState>(initialFormState)
  const [filters, setFilters] = useState<ListFilterState>(initialFilterState)
  const [selectedApplicationId, setSelectedApplicationId] = useState('')
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [auditLoading, setAuditLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [transitioning, setTransitioning] = useState(false)
  const [assigning, setAssigning] = useState(false)
  const [pageError, setPageError] = useState('')
  const [workflowError, setWorkflowError] = useState('')
  const [auditError, setAuditError] = useState('')
  const [assignmentError, setAssignmentError] = useState('')
  const [documentPlaceholders, setDocumentPlaceholders] = useState<
    LoanApplicationDocumentPlaceholderRecord[]
  >([])
  const [documentPlaceholderDrafts, setDocumentPlaceholderDrafts] = useState<
    Record<
      string,
      {
        status: LoanApplicationDocumentPlaceholderStatus
        note: string
        fileName: string
        contentType: string
        sourceReference: string
      }
    >
  >({})
  const [documentPlaceholdersLoading, setDocumentPlaceholdersLoading] = useState(false)
  const [documentPlaceholdersError, setDocumentPlaceholdersError] = useState('')
  const [documentPlaceholderSavingId, setDocumentPlaceholderSavingId] = useState('')
  const [approvalBlocker, setApprovalBlocker] = useState<ApprovalBlocker | null>(null)
  const [transitionNote, setTransitionNote] = useState('')
  const [assignmentNote, setAssignmentNote] = useState('')

  const activeLsps = useMemo(() => lsps.filter((item) => item.status === 'ACTIVE'), [lsps])
  const activeProducts = useMemo(
    () => products.filter((item) => item.status === 'ACTIVE'),
    [products],
  )
  const sourceChannelOptions = useMemo(
    () =>
      Array.from(new Set(applications.map((item) => item.sourceChannel)))
        .filter(Boolean)
        .sort((left, right) => left.localeCompare(right)),
    [applications],
  )
  const selectedApplicationFromList =
    applications.find((application) => application.id === selectedApplicationId) ?? null
  const visibleSelectedApplication =
    selectedLoan && selectedApplicationFromList
      ? { ...selectedLoan, ...selectedApplicationFromList }
      : selectedLoan ?? selectedApplicationFromList
  const latestAudit = intakeAudits[0] ?? null
  const selectedStatusHistory = useMemo(() => sortByCreatedAtDesc(statusHistory), [statusHistory])
  const selectedAssignmentHistory = useMemo(
    () => sortByCreatedAtDesc(assignmentHistory),
    [assignmentHistory],
  )
  const transitionActions = useMemo(
    () =>
      visibleSelectedApplication
        ? getTransitionActions(visibleSelectedApplication.status as LoanApplicationStatus)
        : [],
    [visibleSelectedApplication],
  )
  const currentProgressIndex = visibleSelectedApplication
    ? statusProgressIndex(visibleSelectedApplication.status as LoanApplicationStatus)
    : 0
  const borrowerProfileCompleteness = countBorrowerProfileSignals(visibleSelectedApplication)
  const borrowerLocation = visibleSelectedApplication
    ? [visibleSelectedApplication.borrowerCity, visibleSelectedApplication.borrowerState]
        .filter(Boolean)
        .join(', ') || 'Location not provided'
    : 'Location not provided'
  const borrowerEmployment = formatEmploymentType(
    visibleSelectedApplication?.borrowerEmploymentType,
  )
  const borrowerIncomeCoverage = formatIncomeCoverage(visibleSelectedApplication)
  const sortedDocumentPlaceholders = useMemo(
    () => sortLoanDocumentPlaceholders(documentPlaceholders),
    [documentPlaceholders],
  )
  const requiredDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.required).length,
    [sortedDocumentPlaceholders],
  )
  const verifiedDocumentCount = useMemo(
    () =>
      sortedDocumentPlaceholders.filter(
        (item) => item.status === 'VERIFIED' || item.status === 'NOT_REQUIRED',
      ).length,
    [sortedDocumentPlaceholders],
  )
  const receivedDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.status === 'RECEIVED').length,
    [sortedDocumentPlaceholders],
  )
  const pendingDocumentCount = useMemo(
    () => sortedDocumentPlaceholders.filter((item) => item.status === 'PENDING').length,
    [sortedDocumentPlaceholders],
  )
  const metadataDocumentCount = useMemo(
    () => countDocumentMetadataSignals(sortedDocumentPlaceholders),
    [sortedDocumentPlaceholders],
  )
  const incompleteRequiredDocuments = useMemo(
    () =>
      sortedDocumentPlaceholders.filter(
        (item) => item.required && item.status !== 'VERIFIED' && item.status !== 'NOT_REQUIRED',
      ),
    [sortedDocumentPlaceholders],
  )
  const incompleteRequiredDocumentLabels = useMemo(
    () => formatBlockingDocuments(incompleteRequiredDocuments),
    [incompleteRequiredDocuments],
  )
  const kycApprovalReady = incompleteRequiredDocuments.length === 0

  useEffect(() => {
    if (kycApprovalReady) {
      setApprovalBlocker(null)
    }
  }, [kycApprovalReady])

  async function loadApplications(nextFilters: ListFilterState) {
    const response = await listLoanApplications({
      lspId: nextFilters.lspId || undefined,
      productId: nextFilters.productId || undefined,
      status: nextFilters.status || undefined,
      sourceChannel: nextFilters.sourceChannel || undefined,
      query: nextFilters.query.trim() || undefined,
    })

    setApplications(response)
    setSelectedApplicationId((current) =>
      response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
    )

    return response
  }

  async function refreshSelectedLoan(applicationId: string) {
    const [detailResponse, historyResponse, assignmentResponse] = await Promise.all([
      getLoanApplication(applicationId),
      listLoanApplicationStatusTransitions(applicationId),
      listLoanApplicationAssignmentEvents(applicationId),
    ])

    setSelectedLoan(detailResponse)
    setStatusHistory(sortByCreatedAtDesc(historyResponse))
    setAssignmentHistory(sortByCreatedAtDesc(assignmentResponse))
  }

  async function refreshDocumentPlaceholders(applicationId: string) {
    try {
      const response = await listLoanApplicationDocumentPlaceholders(applicationId)
      setDocumentPlaceholders(sortLoanDocumentPlaceholders(response))
      setDocumentPlaceholderDrafts(seedDocumentDrafts(response))
      setDocumentPlaceholdersError('')
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 404) {
        setDocumentPlaceholders([])
        setDocumentPlaceholderDrafts({})
        setDocumentPlaceholdersError('')
        return
      }

      const message =
        loadError instanceof Error ? loadError.message : 'Unable to load document placeholders.'
      setDocumentPlaceholders([])
      setDocumentPlaceholderDrafts({})
      setDocumentPlaceholdersError(message)
    } finally {
      setDocumentPlaceholdersLoading(false)
    }
  }

  useEffect(() => {
    let cancelled = false

    async function loadPage() {
      setLoading(true)
      setPageError('')

      try {
        const [applicationResponse, lspResponse, productResponse] = await Promise.all([
          listLoanApplications(),
          listLsps(),
          listLoanProducts(),
        ])

        if (!cancelled) {
          setApplications(applicationResponse)
          setSelectedApplicationId(applicationResponse[0]?.id ?? '')
          setLsps(lspResponse)
          setProducts(productResponse)
          setForm((current) => ({
            ...current,
            lspId: current.lspId || lspResponse.find((item) => item.status === 'ACTIVE')?.id || '',
            productId:
              current.productId || productResponse.find((item) => item.status === 'ACTIVE')?.id || '',
          }))
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load intake data.'
        if (!cancelled) {
          setPageError(message)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadPage()

    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    let cancelled = false

    async function loadSelectedLoan() {
        if (!selectedApplicationId) {
          setSelectedLoan(null)
          setAssignmentHistory([])
          setStatusHistory([])
          setWorkflowError('')
          setApprovalBlocker(null)
          setAssignmentError('')
          return
        }

        setSelectedLoan(null)
        setAssignmentHistory([])
        setStatusHistory([])
        setDetailLoading(true)
        setWorkflowError('')
        setApprovalBlocker(null)
        setAssignmentError('')

      try {
        const [detailResponse, historyResponse, assignmentResponse] = await Promise.all([
          getLoanApplication(selectedApplicationId),
          listLoanApplicationStatusTransitions(selectedApplicationId),
          listLoanApplicationAssignmentEvents(selectedApplicationId),
        ])

        if (!cancelled) {
          setSelectedLoan(detailResponse)
          setStatusHistory(sortByCreatedAtDesc(historyResponse))
          setAssignmentHistory(sortByCreatedAtDesc(assignmentResponse))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load loan detail.'
        if (!cancelled) {
          setSelectedLoan(null)
          setAssignmentHistory([])
          setStatusHistory([])
          setWorkflowError(message)
        }
      } finally {
        if (!cancelled) {
          setDetailLoading(false)
        }
      }
    }

    void loadSelectedLoan()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function loadDocumentPlaceholders() {
      if (!selectedApplicationId) {
        setDocumentPlaceholders([])
        setDocumentPlaceholderDrafts({})
        setDocumentPlaceholdersError('')
        setDocumentPlaceholdersLoading(false)
        return
      }

      setDocumentPlaceholdersLoading(true)
      setDocumentPlaceholdersError('')

      try {
        const response = await listLoanApplicationDocumentPlaceholders(selectedApplicationId)
        if (!cancelled) {
          setDocumentPlaceholders(sortLoanDocumentPlaceholders(response))
          setDocumentPlaceholderDrafts(seedDocumentDrafts(response))
        }
      } catch (loadError) {
        if (!cancelled) {
          if (loadError instanceof ApiError && loadError.status === 404) {
            setDocumentPlaceholders([])
            setDocumentPlaceholderDrafts({})
            setDocumentPlaceholdersError('')
          } else {
            const message =
              loadError instanceof Error
                ? loadError.message
                : 'Unable to load document placeholders.'
            setDocumentPlaceholders([])
            setDocumentPlaceholderDrafts({})
            setDocumentPlaceholdersError(message)
          }
        }
      } finally {
        if (!cancelled) {
          setDocumentPlaceholdersLoading(false)
        }
      }
    }

    void loadDocumentPlaceholders()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  useEffect(() => {
    let cancelled = false

    async function refreshFilteredList() {
      if (loading) {
        return
      }

      try {
        const response = await listLoanApplications({
          lspId: filters.lspId || undefined,
          productId: filters.productId || undefined,
          status: filters.status || undefined,
          sourceChannel: filters.sourceChannel || undefined,
          query: filters.query.trim() || undefined,
        })
        if (!cancelled) {
          setApplications(response)
          setPageError('')
          setSelectedApplicationId((current) =>
            response.some((application) => application.id === current) ? current : response[0]?.id ?? '',
          )
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to filter loan applications.'
        if (!cancelled) {
          setPageError(message)
        }
      }
    }

    void refreshFilteredList()

    return () => {
      cancelled = true
    }
  }, [filters, loading])

  useEffect(() => {
    let cancelled = false

    async function loadIntakeAudit() {
      if (!selectedApplicationId) {
        setIntakeAudits([])
        setAuditError('')
        return
      }

      setAuditLoading(true)
      setAuditError('')

      try {
        const response = await listLoanApplicationIntakeAudits(selectedApplicationId)
        if (!cancelled) {
          setIntakeAudits(sortByCreatedAtDesc(response))
        }
      } catch (loadError) {
        const message =
          loadError instanceof Error ? loadError.message : 'Unable to load intake audit.'
        if (!cancelled) {
          setIntakeAudits([])
          setAuditError(message)
        }
      } finally {
        if (!cancelled) {
          setAuditLoading(false)
        }
      }
    }

    void loadIntakeAudit()

    return () => {
      cancelled = true
    }
  }, [selectedApplicationId])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setPageError('')

    try {
      const created = await createLoanApplication({
        lspId: form.lspId,
        productId: form.productId,
        externalLoanId: form.externalLoanId,
        sourceChannel: form.sourceChannel,
        borrowerPan: form.borrowerPan.toUpperCase(),
        borrowerFullName: form.borrowerFullName,
        borrowerMobile: form.borrowerMobile,
        borrowerEmail: form.borrowerEmail || undefined,
        borrowerDateOfBirth: form.borrowerDateOfBirth || undefined,
        borrowerCity: form.borrowerCity || undefined,
        borrowerState: form.borrowerState || undefined,
        borrowerEmploymentType: form.borrowerEmploymentType || undefined,
        borrowerMonthlyIncome: form.borrowerMonthlyIncome ? Number(form.borrowerMonthlyIncome) : undefined,
        requestedAmount: Number(form.requestedAmount),
        tenureMonths: Number(form.tenureMonths),
      })

      await loadApplications(filters)
      setSelectedApplicationId(created.id)
      setForm((current) => ({
        ...initialFormState,
        lspId: current.lspId,
        productId: current.productId,
        sourceChannel: current.sourceChannel,
      }))
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to create loan application.'
      setPageError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleStatusTransition(targetStatus: LoanApplicationStatus) {
    if (!selectedApplicationId) {
      return
    }

    setTransitioning(true)
    setWorkflowError('')
    setApprovalBlocker(null)

    try {
      await transitionLoanApplicationStatus(selectedApplicationId, {
        targetStatus,
        note: transitionNote.trim() || undefined,
      })

      setTransitionNote('')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update loan status.'
      setWorkflowError(message)
      if (targetStatus === 'APPROVED') {
        setApprovalBlocker(extractApprovalBlocker(submitError))
      }
    } finally {
      setTransitioning(false)
    }
  }

  async function handleAssignment(assigneeUsername?: string | null) {
    if (!selectedApplicationId) {
      return
    }

    setAssigning(true)
    setAssignmentError('')

    try {
      await assignLoanApplication(selectedApplicationId, {
        assigneeUsername,
        note: assignmentNote.trim() || undefined,
      })
      setAssignmentNote('')
      await refreshSelectedLoan(selectedApplicationId)
      await loadApplications(filters)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update assignment.'
      setAssignmentError(message)
    } finally {
      setAssigning(false)
    }
  }

  async function handleDocumentPlaceholderSave(placeholderId: string) {
    if (!selectedApplicationId) {
      return
    }

    const placeholder = documentPlaceholders.find((item) => item.id === placeholderId)
    const draft = documentPlaceholderDrafts[placeholderId]
    if (!placeholder || !draft) {
      return
    }

    setDocumentPlaceholderSavingId(placeholderId)
    setDocumentPlaceholdersError('')

    try {
      await updateLoanApplicationDocumentPlaceholder(selectedApplicationId, placeholder.documentType, {
        status: draft.status,
        note: draft.note.trim() || undefined,
        fileName: draft.fileName.trim() || undefined,
        contentType: draft.contentType.trim() || undefined,
        sourceReference: draft.sourceReference.trim() || undefined,
      })
      setDocumentPlaceholdersLoading(true)
      await refreshDocumentPlaceholders(selectedApplicationId)
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to update document placeholder.'
      setDocumentPlaceholdersError(message)
    } finally {
      setDocumentPlaceholderSavingId('')
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">Loan intake</div>
          <CardTitle>Received applications</CardTitle>
          <CardDescription>
            Phase 4 foundation: capture borrower-linked intake records and review newly received
            applications.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{applications.length} applications</Badge>
            <Badge variant="warning">{activeLsps.length} active LSPs</Badge>
            <Badge variant="success">{activeProducts.length} active products</Badge>
          </div>
          <div className="form-grid" style={{ marginBottom: '1rem' }}>
            <div className="field-stack">
              <label htmlFor="filter-lsp">Filter by LSP</label>
              <select
                id="filter-lsp"
                className="ui-input"
                value={filters.lspId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, lspId: event.target.value }))
                }
              >
                <option value="">All LSPs</option>
                {activeLsps.map((lsp) => (
                  <option key={lsp.id} value={lsp.id}>
                    {lsp.code} - {lsp.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-product">Filter by product</label>
              <select
                id="filter-product"
                className="ui-input"
                value={filters.productId}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, productId: event.target.value }))
                }
              >
                <option value="">All products</option>
                {activeProducts.map((product) => (
                  <option key={product.id} value={product.id}>
                    {product.code} - {product.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-query">Search</label>
              <Input
                id="filter-query"
                value={filters.query}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, query: event.target.value }))
                }
                placeholder="Borrower, PAN, mobile, external loan id"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="filter-status">Filter by status</label>
              <select
                id="filter-status"
                className="ui-input"
                value={filters.status}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, status: event.target.value }))
                }
              >
                <option value="">All statuses</option>
                {loanApplicationStatusOptions.map((status) => (
                  <option key={status} value={status}>
                    {loanStatusLabel(status)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="filter-source-channel">Filter by source</label>
              <select
                id="filter-source-channel"
                className="ui-input"
                value={filters.sourceChannel}
                onChange={(event) =>
                  setFilters((current) => ({ ...current, sourceChannel: event.target.value }))
                }
              >
                <option value="">All sources</option>
                {sourceChannelOptions.map((sourceChannel) => (
                  <option key={sourceChannel} value={sourceChannel}>
                    {sourceChannel}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {loading ? <div className="empty-state">Loading loan applications...</div> : null}
          {pageError ? <div className="empty-state">{pageError}</div> : null}
          {!loading && !pageError ? (
            <div className="table-grid">
              {applications.map((application) => (
                <button
                  className="table-row"
                  key={application.id}
                  onClick={() => setSelectedApplicationId(application.id)}
                  style={{
                    cursor: 'pointer',
                    border:
                      application.id === selectedApplicationId
                        ? '1px solid var(--color-accent)'
                        : undefined,
                    background:
                      application.id === selectedApplicationId
                        ? 'color-mix(in srgb, var(--color-accent) 10%, transparent)'
                        : undefined,
                    textAlign: 'left',
                    width: '100%',
                  }}
                  type="button"
                >
                  <div>
                    <strong>{application.borrowerFullName}</strong>
                    <p className="helper-copy">
                      {application.borrowerPan} - {application.borrowerMobile}
                    </p>
                  </div>
                  <Badge variant={loanStatusVariant(application.status as LoanApplicationStatus)}>
                    {loanStatusLabel(application.status as LoanApplicationStatus)}
                  </Badge>
                  <span>{currencyLabel(application.requestedAmount)}</span>
                  <span>{application.tenureMonths} months</span>
                  <span className="helper-copy">
                    {application.lspCode} - {application.productCode}
                  </span>
                  <span className="helper-copy">{application.externalLoanId}</span>
                  <span className="helper-copy">
                    {application.assignedToUsername ? `Owner ${application.assignedToUsername}` : 'Unassigned'}
                  </span>
                  <span className="helper-copy">{formatTimestamp(application.createdAt)}</span>
                </button>
              ))}
              {!applications.length ? (
                <div className="empty-state">No loan applications matched the current filters.</div>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="form-stack">
        <Card>
          <CardHeader>
            <div className="section-eyebrow">Create intake</div>
            <CardTitle>Register a loan application</CardTitle>
            <CardDescription>
              Use this internal intake form to create the borrower and application foundation for
              Phase 4.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleSubmit}>
              <div className="field-stack">
                <label htmlFor="intake-lsp">LSP</label>
                <select
                  id="intake-lsp"
                  className="ui-input"
                  value={form.lspId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, lspId: event.target.value }))
                  }
                >
                  <option value="">Select an LSP</option>
                  {activeLsps.map((lsp) => (
                    <option key={lsp.id} value={lsp.id}>
                      {lsp.code} - {lsp.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="intake-product">Product</label>
                <select
                  id="intake-product"
                  className="ui-input"
                  value={form.productId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, productId: event.target.value }))
                  }
                >
                  <option value="">Select a product</option>
                  {activeProducts.map((product) => (
                    <option key={product.id} value={product.id}>
                      {product.code} - {product.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field-stack">
                <label htmlFor="external-loan-id">External loan id</label>
                <Input
                  id="external-loan-id"
                  value={form.externalLoanId}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, externalLoanId: event.target.value }))
                  }
                  placeholder="EXT-1001"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="source-channel">Source channel</label>
                <Input
                  id="source-channel"
                  value={form.sourceChannel}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      sourceChannel: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="PARTNER_PORTAL"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-pan">Borrower PAN</label>
                <Input
                  id="borrower-pan"
                  value={form.borrowerPan}
                  onChange={(event) =>
                    setForm((current) => ({
                      ...current,
                      borrowerPan: event.target.value.toUpperCase(),
                    }))
                  }
                  placeholder="ABCDE1234F"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-name">Borrower name</label>
                <Input
                  id="borrower-name"
                  value={form.borrowerFullName}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerFullName: event.target.value }))
                  }
                  placeholder="Anika Sharma"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-mobile">Borrower mobile</label>
                <Input
                  id="borrower-mobile"
                  value={form.borrowerMobile}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerMobile: event.target.value }))
                  }
                  placeholder="9999999999"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="borrower-email">Borrower email</label>
                <Input
                  id="borrower-email"
                  value={form.borrowerEmail}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, borrowerEmail: event.target.value }))
                  }
                  placeholder="anika@example.com"
                />
              </div>
              <div className="section-divider">
                <div className="section-eyebrow">Borrower profile</div>
                <p className="helper-copy">
                  Optional enrichment that makes the selected-loan view more borrower-centric.
                </p>
              </div>
              <div className="borrower-enrichment-grid">
                <div className="field-stack">
                  <label htmlFor="borrower-dob">Date of birth</label>
                  <Input
                    id="borrower-dob"
                    type="date"
                    value={form.borrowerDateOfBirth}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerDateOfBirth: event.target.value }))
                    }
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-employment">Employment type</label>
                  <select
                    id="borrower-employment"
                    className="ui-input"
                    value={form.borrowerEmploymentType}
                    onChange={(event) =>
                      setForm((current) => ({
                        ...current,
                        borrowerEmploymentType: event.target.value,
                      }))
                    }
                  >
                    <option value="">Select employment type</option>
                    {borrowerEmploymentTypeOptions.map((employmentType) => (
                      <option key={employmentType} value={employmentType}>
                        {formatEmploymentType(employmentType)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-city">City</label>
                  <Input
                    id="borrower-city"
                    value={form.borrowerCity}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerCity: event.target.value }))
                    }
                    placeholder="Pune"
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-state">State</label>
                  <Input
                    id="borrower-state"
                    value={form.borrowerState}
                    onChange={(event) =>
                      setForm((current) => ({ ...current, borrowerState: event.target.value }))
                    }
                    placeholder="Maharashtra"
                  />
                </div>
                <div className="field-stack">
                  <label htmlFor="borrower-income">Monthly income</label>
                  <Input
                    id="borrower-income"
                    type="number"
                    min="0"
                    step="1"
                    value={form.borrowerMonthlyIncome}
                    onChange={(event) =>
                      setForm((current) => ({
                        ...current,
                        borrowerMonthlyIncome: event.target.value,
                      }))
                    }
                    placeholder="75000"
                  />
                </div>
              </div>
              <div className="field-stack">
                <label htmlFor="requested-amount">Requested amount</label>
                <Input
                  id="requested-amount"
                  type="number"
                  min="0"
                  step="0.01"
                  value={form.requestedAmount}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, requestedAmount: event.target.value }))
                  }
                />
              </div>
              <div className="field-stack">
                <label htmlFor="tenure-months">Tenure months</label>
                <Input
                  id="tenure-months"
                  type="number"
                  min="1"
                  step="1"
                  value={form.tenureMonths}
                  onChange={(event) =>
                    setForm((current) => ({ ...current, tenureMonths: event.target.value }))
                  }
                />
              </div>
              {pageError ? <div className="empty-state">{pageError}</div> : null}
              <div className="inline-actions">
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Creating...' : 'Create application'}
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Loan workflow</div>
            <CardTitle>Selected loan detail and status</CardTitle>
            <CardDescription>
              Review the selected application, move it through the first review lifecycle, and
              inspect the status history.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="loan-workflow">
                <div className="loan-workflow__summary borrower-summary">
                  <div className="borrower-summary__identity">
                    <div className="inline-actions">
                      <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                        {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                      </Badge>
                      <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                      <Badge variant={borrowerProfileCompleteness >= 5 ? 'success' : 'warning'}>
                        {borrowerProfileCompleteness}/5 profile fields
                      </Badge>
                    </div>
                    <h3>{visibleSelectedApplication.borrowerFullName}</h3>
                    <p className="helper-copy">
                      {visibleSelectedApplication.borrowerPan} - {visibleSelectedApplication.borrowerMobile}
                      {' '}| {visibleSelectedApplication.borrowerEmail || 'No email provided'}
                    </p>
                    <div className="borrower-summary__meta">
                      <span>{formatDateLabel(visibleSelectedApplication.borrowerDateOfBirth)} / {formatAgeLabel(visibleSelectedApplication.borrowerDateOfBirth)}</span>
                      <span>{borrowerLocation}</span>
                      <span>{borrowerEmployment}</span>
                    </div>
                  </div>
                  <div className="loan-workflow__headline-metrics">
                    <div className="loan-workflow__metric">
                      <span>Monthly income</span>
                      <strong>{formatIncomeLabel(visibleSelectedApplication.borrowerMonthlyIncome)}</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Requested amount</span>
                      <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                    </div>
                    <div className="loan-workflow__metric">
                      <span>Income coverage</span>
                      <strong>{borrowerIncomeCoverage}</strong>
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Borrower profile</div>
                  <div className="loan-detail-grid">
                    <div className="loan-detail-field">
                      <span>Date of birth</span>
                      <strong>{formatDateLabel(visibleSelectedApplication.borrowerDateOfBirth)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Employment type</span>
                      <strong>{borrowerEmployment}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>City</span>
                      <strong>{visibleSelectedApplication.borrowerCity || 'Not provided'}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>State</span>
                      <strong>{visibleSelectedApplication.borrowerState || 'Not provided'}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Monthly income</span>
                      <strong>{formatIncomeLabel(visibleSelectedApplication.borrowerMonthlyIncome)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Borrower email</span>
                      <strong>{visibleSelectedApplication.borrowerEmail || 'Not provided'}</strong>
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">KYC checklist</div>
                  <div className="loan-transition-panel">
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>Document placeholders</h4>
                        <p className="helper-copy">
                          Track the per-loan KYC checklist and document metadata until live upload handling is added.
                        </p>
                      </div>
                      <Badge
                        variant={
                          sortedDocumentPlaceholders.length > 0 &&
                          verifiedDocumentCount === sortedDocumentPlaceholders.length
                            ? 'success'
                            : 'warning'
                        }
                      >
                        {verifiedDocumentCount}/{sortedDocumentPlaceholders.length || 0} ready
                      </Badge>
                      </div>
                    <div className="loan-workflow__headline-metrics">
                      <div className="loan-workflow__metric">
                        <span>Required</span>
                        <strong>{requiredDocumentCount}</strong>
                        </div>
                        <div className="loan-workflow__metric">
                          <span>Metadata tagged</span>
                          <strong>{metadataDocumentCount}</strong>
                        </div>
                      <div className="loan-workflow__metric">
                        <span>Awaiting action</span>
                        <strong>{receivedDocumentCount + pendingDocumentCount}</strong>
                      </div>
                    </div>
                    {!kycApprovalReady ? (
                      <div className="loan-alert loan-alert--warning">
                        <div className="loan-alert__title">Approval blocked by KYC</div>
                        <p className="helper-copy">
                          {incompleteRequiredDocumentLabels.length
                            ? `Required documents still need review: ${incompleteRequiredDocumentLabels.join(', ')}.`
                            : 'Required KYC documents are still incomplete.'}
                        </p>
                        <p className="helper-copy">
                          Approval will remain blocked until each required item is marked Verified or Not required.
                        </p>
                      </div>
                    ) : null}
                    {documentPlaceholdersLoading ? <div className="empty-state">Loading document placeholders...</div> : null}
                    {documentPlaceholdersError ? <div className="empty-state">{documentPlaceholdersError}</div> : null}
                    {!documentPlaceholdersLoading && !documentPlaceholdersError && !sortedDocumentPlaceholders.length ? (
                      <div className="empty-state">No KYC document placeholders are configured for this loan yet.</div>
                    ) : null}
                      {!documentPlaceholdersLoading && !documentPlaceholdersError && sortedDocumentPlaceholders.length ? (
                        <div className="loan-checklist">
                          {sortedDocumentPlaceholders.map((placeholder) => {
                            const draft = documentPlaceholderDrafts[placeholder.id] ?? {
                              status: placeholder.status,
                              note: placeholder.note ?? '',
                              fileName: placeholder.fileName ?? '',
                              contentType: placeholder.contentType ?? '',
                              sourceReference: placeholder.sourceReference ?? '',
                            }
                            const isSaving = documentPlaceholderSavingId === placeholder.id
                            const metadataIsPresent = Boolean(
                              placeholder.fileName?.trim() ||
                                placeholder.contentType?.trim() ||
                                placeholder.sourceReference?.trim(),
                            )

                            return (
                              <div className="loan-checklist__item" key={placeholder.id}>
                                <div className="loan-checklist__body">
                                  <div className="inline-actions">
                                    <strong>{placeholder.documentDisplayName}</strong>
                                    <Badge variant={placeholder.required ? 'destructive' : 'default'}>
                                      {placeholder.required ? 'Required' : 'Optional'}
                                    </Badge>
                                    <Badge variant={loanDocumentPlaceholderStatusVariant(draft.status)}>
                                      {loanDocumentPlaceholderStatusLabel(draft.status)}
                                    </Badge>
                                  </div>
                                  <div className="loan-checklist__meta-grid">
                                    <div className="loan-detail-field">
                                      <span>File name</span>
                                      <strong>{formatMetadataValue(placeholder.fileName)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Content type</span>
                                      <strong>{formatMetadataValue(placeholder.contentType)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Source / reference</span>
                                      <strong>{formatMetadataValue(placeholder.sourceReference)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Uploaded at</span>
                                      <strong>{formatOptionalTimestamp(placeholder.uploadedAt)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Uploaded by</span>
                                      <strong>{formatMetadataValue(placeholder.uploadedByUsername)}</strong>
                                    </div>
                                    <div className="loan-detail-field">
                                      <span>Metadata state</span>
                                      <strong>{metadataIsPresent ? 'Captured' : 'Awaiting metadata'}</strong>
                                    </div>
                                  </div>
                                  <p className="helper-copy">
                                    {placeholder.updatedAt
                                      ? `Updated ${formatTimestamp(placeholder.updatedAt)} by ${placeholder.updatedByUsername ?? 'system'}`
                                      : 'Awaiting first review.'}
                                  </p>
                                  <p className="helper-copy">
                                    {placeholder.note?.trim()
                                      ? placeholder.note
                                      : 'Add a short review note when you update the metadata placeholder.'}
                                  </p>
                                </div>
                                <div className="loan-checklist__controls">
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-status-${placeholder.id}`}>Status</label>
                                  <select
                                    id={`placeholder-status-${placeholder.id}`}
                                    className="ui-input ui-select"
                                    value={draft.status}
                                    onChange={(event) =>
                                      setDocumentPlaceholderDrafts((current) => ({
                                        ...current,
                                        [placeholder.id]: {
                                          ...(current[placeholder.id] ?? draft),
                                          status: event.target.value as LoanApplicationDocumentPlaceholderStatus,
                                        },
                                      }))
                                    }
                                  >
                                      {loanApplicationDocumentPlaceholderStatusOptions.map((option) => (
                                        <option key={option} value={option}>
                                          {loanDocumentPlaceholderStatusLabel(option)}
                                        </option>
                                      ))}
                                    </select>
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-file-${placeholder.id}`}>File name</label>
                                    <Input
                                      id={`placeholder-file-${placeholder.id}`}
                                      value={draft.fileName}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            fileName: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. pan-card.pdf"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-content-${placeholder.id}`}>Content type</label>
                                    <Input
                                      id={`placeholder-content-${placeholder.id}`}
                                      value={draft.contentType}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            contentType: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. application/pdf"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-reference-${placeholder.id}`}>Source / reference</label>
                                    <Input
                                      id={`placeholder-reference-${placeholder.id}`}
                                      value={draft.sourceReference}
                                      onChange={(event) =>
                                        setDocumentPlaceholderDrafts((current) => ({
                                          ...current,
                                          [placeholder.id]: {
                                            ...(current[placeholder.id] ?? draft),
                                            sourceReference: event.target.value,
                                          },
                                        }))
                                      }
                                      placeholder="e.g. DigiLocker ref or storage key"
                                    />
                                  </div>
                                  <div className="field-stack">
                                    <label htmlFor={`placeholder-note-${placeholder.id}`}>Note</label>
                                    <Input
                                      id={`placeholder-note-${placeholder.id}`}
                                      value={draft.note}
                                    onChange={(event) =>
                                      setDocumentPlaceholderDrafts((current) => ({
                                        ...current,
                                        [placeholder.id]: {
                                          ...(current[placeholder.id] ?? draft),
                                          note: event.target.value,
                                        },
                                      }))
                                    }
                                      placeholder="Optional review note"
                                    />
                                  </div>
                                  <div className="loan-checklist__actions">
                                    <Button
                                    disabled={isSaving}
                                    onClick={() => void handleDocumentPlaceholderSave(placeholder.id)}
                                    type="button"
                                    variant="secondary"
                                    size="sm"
                                  >
                                      {isSaving ? 'Saving...' : 'Save'}
                                    </Button>
                                  </div>
                                </div>
                              </div>
                          )
                        })}
                      </div>
                    ) : null}
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Queue ownership</div>
                  <div className="loan-transition-panel">
                    <div className="loan-transition-panel__header">
                      <div>
                        <h4>Assignment</h4>
                        <p className="helper-copy">
                          Claim the case or release it back to the shared review queue.
                        </p>
                      </div>
                      <Badge variant={visibleSelectedApplication.assignedToUsername ? 'default' : 'warning'}>
                        {visibleSelectedApplication.assignedToUsername || 'Unassigned'}
                      </Badge>
                    </div>
                    <div className="loan-assignment-summary">
                      <div className="loan-detail-field">
                        <span>Assigned to</span>
                        <strong>{visibleSelectedApplication.assignedToUsername || 'Shared queue'}</strong>
                      </div>
                      <div className="loan-detail-field">
                        <span>Assigned by</span>
                        <strong>{visibleSelectedApplication.assignedByUsername || 'Not captured'}</strong>
                      </div>
                      <div className="loan-detail-field">
                        <span>Assigned at</span>
                        <strong>
                          {visibleSelectedApplication.assignedAt
                            ? formatTimestamp(visibleSelectedApplication.assignedAt)
                            : 'Not assigned'}
                        </strong>
                      </div>
                    </div>
                    <div className="field-stack">
                      <label htmlFor="assignment-note">Assignment note</label>
                      <textarea
                        id="assignment-note"
                        className="ui-textarea"
                        value={assignmentNote}
                        onChange={(event) => setAssignmentNote(event.target.value)}
                        placeholder="Add context for the handoff. This is optional."
                        rows={3}
                      />
                    </div>
                    {assignmentError ? <div className="empty-state">{assignmentError}</div> : null}
                    <div className="loan-transition-actions">
                      <Button
                        disabled={assigning || !user?.username}
                        onClick={() => void handleAssignment(user?.username)}
                        type="button"
                        variant="secondary"
                      >
                        {assigning ? 'Updating...' : 'Assign to me'}
                      </Button>
                      <Button
                        disabled={assigning || !visibleSelectedApplication.assignedToUsername}
                        onClick={() => void handleAssignment(null)}
                        type="button"
                        variant="outline"
                      >
                        {assigning ? 'Updating...' : 'Release'}
                      </Button>
                    </div>
                    <div className="loan-history">
                      <div className="loan-history__header">
                        <div className="section-eyebrow">Assignment history</div>
                        <Badge>{selectedAssignmentHistory.length} events</Badge>
                      </div>
                      {!selectedAssignmentHistory.length ? (
                        <div className="empty-state">No assignment events recorded yet.</div>
                      ) : (
                        <div className="loan-history__list">
                          {selectedAssignmentHistory.map((event) => (
                            <div className="loan-history__item" key={event.id}>
                              <div>
                                <strong>
                                  {event.fromAssigneeUsername || 'Shared queue'} - {event.toAssigneeUsername || 'Shared queue'}
                                </strong>
                                <p className="helper-copy">{formatTimestamp(event.createdAt)}</p>
                                <p className="helper-copy">{formatNote(event.note)}</p>
                                <p className="helper-copy">
                                  Correlation ID: {event.correlationId ?? 'Not captured'}
                                </p>
                              </div>
                              <div className="inline-actions">
                                <Badge>{event.actorUsername}</Badge>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div className="detail-section">
                  <div className="section-eyebrow">Application context</div>
                  <div className="loan-detail-grid">
                    <div className="loan-detail-field">
                      <span>Application id</span>
                      <strong>{visibleSelectedApplication.id}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>LSP</span>
                      <strong>
                        {visibleSelectedApplication.lspCode} - {visibleSelectedApplication.lspName}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Product</span>
                      <strong>
                        {visibleSelectedApplication.productCode} - {visibleSelectedApplication.productName}
                      </strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Source channel</span>
                      <strong>{visibleSelectedApplication.sourceChannel}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Requested amount</span>
                      <strong>{currencyLabel(visibleSelectedApplication.requestedAmount)}</strong>
                    </div>
                    <div className="loan-detail-field">
                      <span>Tenure</span>
                      <strong>{visibleSelectedApplication.tenureMonths} months</strong>
                    </div>
                  </div>
                </div>

                <div className="loan-status-lane" aria-label="Loan status progression">
                  {workflowProgression.map((step) => {
                    const stepIndex =
                      step.status === 'RECEIVED' ? 1 : step.status === 'UNDER_REVIEW' ? 2 : 3
                    const isCurrent = currentProgressIndex === stepIndex
                    const isComplete = currentProgressIndex > stepIndex

                    return (
                      <div
                        key={step.status}
                        className={[
                          'loan-status-lane__step',
                          isCurrent ? 'loan-status-lane__step--current' : '',
                          isComplete ? 'loan-status-lane__step--complete' : '',
                        ]
                          .filter(Boolean)
                          .join(' ')}
                      >
                        <div className="loan-status-lane__step-number">
                          {step.status === 'DECISION' ? '3' : statusProgressIndex(step.status)}
                        </div>
                        <div>
                          <strong>{step.label}</strong>
                          <p className="helper-copy">{step.description}</p>
                        </div>
                        {step.status === 'DECISION' ? (
                          <div className="inline-actions">
                            <Badge variant="success">Approved</Badge>
                            <Badge variant="destructive">Rejected</Badge>
                          </div>
                        ) : null}
                      </div>
                    )
                  })}
                </div>

                <div className="loan-transition-panel">
                  <div className="loan-transition-panel__header">
                    <div>
                      <h4>Transition action</h4>
                      <p className="helper-copy">
                        Add an optional note before updating the status.
                      </p>
                    </div>
                    <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                      {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                    </Badge>
                  </div>
                  <div className="field-stack">
                    <label htmlFor="status-note">Decision note</label>
                    <textarea
                      id="status-note"
                      className="ui-textarea"
                      value={transitionNote}
                      onChange={(event) => setTransitionNote(event.target.value)}
                      placeholder="Add context for the review decision. This is optional."
                      rows={4}
                    />
                  </div>
                  {!kycApprovalReady ? (
                    <div className="loan-alert loan-alert--warning">
                      <div className="loan-alert__title">Approval currently blocked</div>
                      <p className="helper-copy">
                        {incompleteRequiredDocumentLabels.length
                          ? `Complete these required KYC items before approving: ${incompleteRequiredDocumentLabels.join(', ')}.`
                          : 'Complete the required KYC checklist before approving this loan.'}
                      </p>
                    </div>
                  ) : null}
                  {approvalBlocker ? (
                    <div className="loan-alert loan-alert--warning">
                      <div className="loan-alert__title">Approval blocked by backend validation</div>
                      <p className="helper-copy">{approvalBlocker.message}</p>
                      {approvalBlocker.documents.length ? (
                        <ul className="loan-alert__list">
                          {approvalBlocker.documents.map((document) => (
                            <li key={document}>{document}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  ) : null}
                  {workflowError ? <div className="empty-state">{workflowError}</div> : null}
                  <div className="loan-transition-actions">
                    {transitionActions.map((action) => (
                      <Button
                        key={action.targetStatus}
                        disabled={transitioning}
                        onClick={() => void handleStatusTransition(action.targetStatus)}
                        type="button"
                        variant={action.variant}
                        className={
                          action.targetStatus === 'REJECTED'
                            ? 'loan-transition-button--reject'
                            : undefined
                        }
                      >
                        {action.targetStatus === 'APPROVED' ? (
                          <CheckCircle2 size={16} />
                        ) : action.targetStatus === 'REJECTED' ? (
                          <FileText size={16} />
                        ) : (
                          <ArrowRight size={16} />
                        )}
                        {transitioning ? 'Updating...' : action.label}
                      </Button>
                    ))}
                    {!transitionActions.length ? (
                      <div className="empty-state">This loan is in a terminal state.</div>
                    ) : null}
                  </div>
                </div>

                <div className="loan-history">
                  <div className="loan-history__header">
                    <div className="section-eyebrow">Status history</div>
                    <Badge>{selectedStatusHistory.length} events</Badge>
                  </div>
                  {detailLoading ? <div className="empty-state">Loading loan detail...</div> : null}
                  {!detailLoading && !selectedStatusHistory.length ? (
                    <div className="empty-state">No status transitions recorded yet.</div>
                  ) : null}
                  {!detailLoading && selectedStatusHistory.length ? (
                    <div className="loan-history__list">
                      {selectedStatusHistory.map((transition) => (
                        <div className="loan-history__item" key={transition.id}>
                          <div>
                            <strong>
                              {loanStatusLabel(transition.fromStatus)} - {loanStatusLabel(transition.toStatus)}
                            </strong>
                            <p className="helper-copy">{formatTimestamp(transition.createdAt)}</p>
                            <p className="helper-copy">{formatNote(transition.note)}</p>
                            <p className="helper-copy">
                              Correlation ID: {transition.correlationId ?? 'Not captured'}
                            </p>
                          </div>
                          <div className="inline-actions">
                            <Badge variant={loanStatusVariant(transition.toStatus)}>
                              {loanStatusLabel(transition.toStatus)}
                            </Badge>
                            <Badge variant="warning">{transition.actorUsername}</Badge>
                          </div>
                        </div>
                      ))}
                    </div>
                  ) : null}
                </div>
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its detail view.</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div className="section-eyebrow">Troubleshooting</div>
            <CardTitle>Selected intake audit</CardTitle>
            <CardDescription>
              Inspect the persisted raw intake payload captured when the selected application was
              created.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {visibleSelectedApplication ? (
              <div className="form-grid">
                <div className="inline-actions">
                  <Badge>{visibleSelectedApplication.externalLoanId}</Badge>
                  <Badge variant={loanStatusVariant(visibleSelectedApplication.status as LoanApplicationStatus)}>
                    {loanStatusLabel(visibleSelectedApplication.status as LoanApplicationStatus)}
                  </Badge>
                </div>
                <div className="helper-copy">
                  Borrower: {visibleSelectedApplication.borrowerFullName} -{' '}
                  {visibleSelectedApplication.borrowerPan}
                </div>
                <div className="helper-copy">
                  Product: {visibleSelectedApplication.productCode} - Source:{' '}
                  {visibleSelectedApplication.sourceChannel}
                </div>
                {auditLoading ? <div className="empty-state">Loading intake audit...</div> : null}
                {auditError ? <div className="empty-state">{auditError}</div> : null}
                {!auditLoading && !auditError && latestAudit ? (
                  <>
                    <div className="helper-copy">
                      Captured {formatTimestamp(latestAudit.createdAt)} by {latestAudit.actorUsername}
                    </div>
                    <div className="helper-copy">
                      Correlation ID: {latestAudit.correlationId ?? 'Not captured'}
                    </div>
                    <pre
                      className="helper-copy"
                      style={{
                        margin: 0,
                        maxHeight: '22rem',
                        overflow: 'auto',
                        padding: '1rem',
                        borderRadius: '1rem',
                        background: 'var(--color-panel-muted)',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {formatPayloadJson(latestAudit.payloadJson)}
                    </pre>
                  </>
                ) : null}
                {!auditLoading && !auditError && !latestAudit ? (
                  <div className="empty-state">
                    No intake audit is available for the selected application.
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="empty-state">Select a loan application to inspect its intake audit.</div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
