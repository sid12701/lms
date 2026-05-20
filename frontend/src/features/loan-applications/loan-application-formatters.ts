import type {
  LoanApplicationRecord,
  LoanPaymentTransactionRecord,
} from '../api/lms-api'

export function currencyLabel(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

export function todayDateValue() {
  return new Intl.DateTimeFormat('en-CA').format(new Date())
}

export function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('en-IN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

export function formatDateLabel(value?: string | null) {
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

function maskMiddle(value: string, visibleStart: number, visibleEnd: number) {
  if (!value) {
    return value
  }

  if (value.length <= visibleStart + visibleEnd) {
    return '•'.repeat(value.length)
  }

  return (
    value.slice(0, visibleStart) +
    '•'.repeat(value.length - visibleStart - visibleEnd) +
    value.slice(value.length - visibleEnd)
  )
}

export function formatBorrowerPan(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  return reveal ? value : maskMiddle(value, 3, 2)
}

export function formatBorrowerMobile(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  return reveal ? value : maskMiddle(value, 0, 4)
}

export function formatBorrowerEmail(value?: string | null, reveal = false) {
  if (!value) {
    return 'Not provided'
  }

  if (reveal) {
    return value
  }

  const [localPart, domain] = value.split('@')
  if (!domain) {
    return maskMiddle(value, 1, 0)
  }

  const maskedLocalPart =
    localPart.length <= 1 ? '•' : localPart.charAt(0) + '•'.repeat(Math.max(localPart.length - 1, 2))
  return `${maskedLocalPart}@${domain}`
}

export function formatAgeLabel(value?: string | null) {
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

export function formatIncomeLabel(value?: number | null) {
  if (value == null) {
    return 'Not provided'
  }

  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0,
  }).format(value)
}

export function formatEmploymentType(value?: string | null) {
  if (!value) {
    return 'Not provided'
  }

  return value
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

export function countBorrowerProfileSignals(application: LoanApplicationRecord | null) {
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

export function formatIncomeCoverage(application: LoanApplicationRecord | null) {
  if (!application || !application.borrowerMonthlyIncome || application.borrowerMonthlyIncome <= 0) {
    return 'Income context not provided'
  }

  const ratio = application.requestedAmount / application.borrowerMonthlyIncome
  return `${ratio.toFixed(1)}x monthly income`
}

export function sortByCreatedAtDesc<T extends { createdAt: string }>(records: T[]) {
  return [...records].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
}

export function sortPaymentTransactions(records: LoanPaymentTransactionRecord[]) {
  return [...records].sort((left, right) => {
    const paymentDateComparison = right.paymentDate.localeCompare(left.paymentDate)
    if (paymentDateComparison !== 0) {
      return paymentDateComparison
    }

    return Date.parse(right.createdAt) - Date.parse(left.createdAt)
  })
}

function maskSensitivePayload(payload: unknown): unknown {
  if (Array.isArray(payload)) {
    return payload.map((item) => maskSensitivePayload(item))
  }

  if (!payload || typeof payload !== 'object') {
    return payload
  }

  return Object.entries(payload).reduce<Record<string, unknown>>((masked, [key, value]) => {
    if (typeof value === 'string') {
      if (key === 'borrowerPan' || key === 'pan') {
        masked[key] = formatBorrowerPan(value, false)
        return masked
      }

      if (key === 'borrowerMobile' || key === 'mobile') {
        masked[key] = formatBorrowerMobile(value, false)
        return masked
      }

      if (key === 'borrowerEmail' || key === 'email') {
        masked[key] = formatBorrowerEmail(value, false)
        return masked
      }
    }

    masked[key] = maskSensitivePayload(value)
    return masked
  }, {})
}

export function formatPayloadJson(payloadJson: string, revealSensitiveData = false) {
  try {
    const parsed = JSON.parse(payloadJson)
    const visiblePayload = revealSensitiveData ? parsed : maskSensitivePayload(parsed)
    return JSON.stringify(visiblePayload, null, 2)
  } catch {
    return payloadJson
  }
}

export function formatNote(note: string | null) {
  const trimmed = note?.trim()
  return trimmed ? trimmed : 'No note recorded.'
}

export function formatMetadataValue(value?: string | null) {
  const trimmed = value?.trim()
  return trimmed ? trimmed : 'Not provided'
}

export function formatOptionalTimestamp(value?: string | null) {
  if (!value) {
    return 'Not uploaded'
  }

  return formatTimestamp(value)
}
