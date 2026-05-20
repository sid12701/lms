import { ApiError } from '../api/lms-api'
import type {
  LoanApplicationDocumentPlaceholderRecord,
  LoanApplicationDocumentPlaceholderStatus,
} from '../api/lms-api'

export type ApprovalBlocker = {
  message: string
  documents: string[]
}

export function loanDocumentPlaceholderReasonLabel(
  status: LoanApplicationDocumentPlaceholderStatus,
) {
  return status === 'REJECTED' ? 'Rejection reason' : 'Review reason'
}

export function loanDocumentPlaceholderReasonPlaceholder(
  status: LoanApplicationDocumentPlaceholderStatus,
) {
  return status === 'REJECTED'
    ? 'Capture why this document was rejected.'
    : 'Capture why this document still needs attention.'
}

export function loanDocumentPlaceholderReasonFallback(
  status: LoanApplicationDocumentPlaceholderStatus,
) {
  return status === 'REJECTED' ? 'No rejection reason recorded.' : 'No review reason recorded.'
}

export function extractApprovalBlocker(error: unknown): ApprovalBlocker | null {
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

export function formatBlockingDocuments(documents: LoanApplicationDocumentPlaceholderRecord[]) {
  return documents.map((document) => document.documentDisplayName)
}

export function loanDocumentPlaceholderStatusLabel(
  status: LoanApplicationDocumentPlaceholderStatus,
) {
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

export function loanDocumentPlaceholderStatusVariant(
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

export function sortLoanDocumentPlaceholders(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return [...records].sort((left, right) => {
    if (left.required !== right.required) {
      return left.required ? -1 : 1
    }

    return left.documentDisplayName.localeCompare(right.documentDisplayName)
  })
}

export function seedDocumentDrafts(records: LoanApplicationDocumentPlaceholderRecord[]) {
  return records.reduce<
    Record<
      string,
      {
        status: LoanApplicationDocumentPlaceholderStatus
        note: string
        reviewReason: string
        rejectionReason: string
        fileName: string
        contentType: string
        sourceReference: string
      }
    >
  >((accumulator, record) => {
    accumulator[record.id] = {
      status: record.status,
      note: record.note ?? '',
      reviewReason: record.reviewReason ?? '',
      rejectionReason: record.rejectionReason ?? '',
      fileName: record.fileName ?? '',
      contentType: record.contentType ?? '',
      sourceReference: record.sourceReference ?? '',
    }
    return accumulator
  }, {})
}

export function countDocumentMetadataSignals(records: LoanApplicationDocumentPlaceholderRecord[]) {
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
