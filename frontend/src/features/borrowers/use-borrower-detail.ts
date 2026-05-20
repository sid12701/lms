import { useEffect, useState } from 'react'
import { getBorrowerDetail, type BorrowerDetailRecord } from '../api/borrowers-api'

type UseBorrowerDetailOptions = {
  borrowerId?: string
}

export function useBorrowerDetail({ borrowerId }: UseBorrowerDetailOptions) {
  const [borrower, setBorrower] = useState<BorrowerDetailRecord | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!borrowerId) {
      setLoading(false)
      setError('Borrower not found.')
      return
    }

    const currentBorrowerId = borrowerId
    let cancelled = false

    async function loadBorrowerDetail() {
      setLoading(true)
      setError('')

      try {
        const data = await getBorrowerDetail(currentBorrowerId)
        if (cancelled) {
          return
        }
        setBorrower(data)
      } catch (exception) {
        if (cancelled) {
          return
        }
        const message =
          exception instanceof Error ? exception.message : 'Unable to load borrower.'
        setError(message)
        setBorrower(null)
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadBorrowerDetail()

    return () => {
      cancelled = true
    }
  }, [borrowerId])

  return { borrower, loading, error }
}
