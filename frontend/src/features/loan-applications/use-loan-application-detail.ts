import { useCallback, useEffect, useMemo, useState } from 'react'
import { downloadBlob } from '@/lib/download-blob'
import {
  type LoanApplicationDocumentPlaceholderRecord,
  type LoanApplicationDetailRecord,
  type LoanRepaymentScheduleInstallmentRecord,
} from '../api/lms-api'
import {
  downloadAllDocuments,
  getLoanApplication,
  initiateLoanApplicationDisbursement,
  listLoanApplicationDocumentPlaceholders,
  listLoanApplicationRepaymentSchedule,
  transitionLoanApplicationStatus,
} from '../api/loan-applications-api'
import {
  getLoanDetailActions,
  type LoanDetailAction,
} from './loan-application-detail-model'

type UseLoanApplicationDetailOptions = {
  loanId?: string
  roles?: string[]
}

export function useLoanApplicationDetail({
  loanId,
  roles,
}: UseLoanApplicationDetailOptions) {
  const [loan, setLoan] = useState<LoanApplicationDetailRecord | null>(null)
  const [schedule, setSchedule] = useState<LoanRepaymentScheduleInstallmentRecord[]>([])
  const [documents, setDocuments] = useState<LoanApplicationDocumentPlaceholderRecord[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [actionError, setActionError] = useState('')
  const [activeActionId, setActiveActionId] = useState<string | null>(null)
  const [downloadingZip, setDownloadingZip] = useState(false)

  useEffect(() => {
    if (!loanId) {
      setLoading(false)
      setError('Loan application not found.')
      return
    }

    const currentLoanId = loanId
    let cancelled = false

    async function loadLoanDetail() {
      setLoading(true)
      setError('')

      try {
        const [loanData, scheduleData, documentData] = await Promise.all([
          getLoanApplication(currentLoanId),
          listLoanApplicationRepaymentSchedule(currentLoanId),
          listLoanApplicationDocumentPlaceholders(currentLoanId),
        ])

        if (cancelled) {
          return
        }

        setLoan(loanData)
        setSchedule(scheduleData)
        setDocuments(documentData)
      } catch {
        if (!cancelled) {
          setError('Failed to load loan application.')
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadLoanDetail()

    return () => {
      cancelled = true
    }
  }, [loanId])

  const actions = useMemo(() => {
    if (!loan) {
      return []
    }

    return getLoanDetailActions(loan, roles)
  }, [loan, roles])

  const executeAction = useCallback(
    async (action: LoanDetailAction) => {
      if (!loanId) {
        return
      }

      setActiveActionId(action.id)
      setActionError('')

      try {
        const updatedLoan =
          action.kind === 'disburse'
            ? await initiateLoanApplicationDisbursement(loanId)
            : await transitionLoanApplicationStatus(loanId, { targetStatus: action.targetStatus! })

        setLoan(updatedLoan)
      } catch {
        setActionError(
          action.kind === 'disburse'
            ? 'Disbursal initiation failed. Please try again.'
            : 'Action failed. Please try again.',
        )
      } finally {
        setActiveActionId(null)
      }
    },
    [loanId],
  )

  const handleDownloadAllDocuments = useCallback(async () => {
    if (!loanId) {
      return
    }

    setDownloadingZip(true)
    setActionError('')

    try {
      const { blob, filename } = await downloadAllDocuments(loanId)
      downloadBlob(blob, filename ?? `loan-${loanId}-documents.zip`)
    } catch {
      setActionError('No document files available for download.')
    } finally {
      setDownloadingZip(false)
    }
  }, [loanId])

  return {
    loan,
    schedule,
    documents,
    loading,
    error,
    actionError,
    activeActionId,
    downloadingZip,
    actions,
    executeAction,
    handleDownloadAllDocuments,
  }
}
