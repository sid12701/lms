import type { LoanApplicationDetailRecord } from '../api/lms-api'
import {
  describeLastUpdated,
  formatBorrowerLocation,
  formatMonthYearLabel,
  formatRelativeTime,
  getBorrowerInitials,
  getLoanDetailActions,
  loanDocumentStatusLabel,
  loanDocumentStatusVariant,
} from './loan-application-detail-model'

const baseLoan: LoanApplicationDetailRecord = {
  id: 'loan-1',
  borrowerId: 'borrower-1',
  borrowerFullName: 'Asha Rao',
  borrowerPan: 'ABCDE1234F',
  borrowerMobile: '9876543210',
  borrowerEmail: 'asha@example.com',
  borrowerDateOfBirth: null,
  borrowerCity: 'Pune',
  borrowerState: 'Maharashtra',
  borrowerEmploymentType: 'SALARIED',
  borrowerMonthlyIncome: 75000,
  lspId: 'lsp-1',
  lspCode: 'ABC',
  lspName: 'ABC Finance',
  productId: 'product-1',
  productCode: 'PL',
  productName: 'Personal Loan',
  externalLoanId: 'EXT-1',
  sourceChannel: 'PARTNER_PORTAL',
  requestedAmount: 240000,
  tenureMonths: 24,
  status: 'INITIALIZED',
  assignedToUsername: null,
  assignedByUsername: null,
  assignedAt: null,
  createdAt: '2026-04-10T10:00:00Z',
  updatedAt: '2026-04-10T10:00:00Z',
  loanAccount: {
    id: 'account-1',
    accountNumber: 'LN-001',
    status: 'PENDING_DISBURSEMENT',
    principalAmount: 240000,
    tenureMonths: 24,
    approvedAt: '2026-04-11T08:00:00Z',
    createdAt: '2026-04-11T08:00:00Z',
    closureReason: null,
    closedAt: null,
    closedByUsername: null,
    delinquency: null,
    repaymentSchedule: {
      installmentCount: 24,
      installmentAmount: 12000,
      firstDueDate: '2026-05-01',
      finalDueDate: '2028-04-01',
    },
  },
  lastActivity: null,
}

describe('loan-application-detail-model', () => {
  it('formats relative times and derives borrower initials', () => {
    const now = Date.parse('2026-04-13T12:00:00Z')

    expect(formatRelativeTime('2026-04-13T11:45:00Z', now)).toBe('15m ago')
    expect(formatRelativeTime('2026-04-11T12:00:00Z', now)).toBe('2d ago')
    expect(getBorrowerInitials('Asha Rao')).toBe('AR')
    expect(getBorrowerInitials('')).toBe('?')
  })

  it('builds visible actions from status and roles', () => {
    expect(getLoanDetailActions(baseLoan, ['OPS_USER'])).toHaveLength(1)
    expect(getLoanDetailActions(baseLoan, ['SYSTEM_ADMIN'])).toHaveLength(1)

    expect(
      getLoanDetailActions(
        {
          ...baseLoan,
          status: 'AWAITING_APPROVAL',
        },
        ['SYSTEM_ADMIN'],
      ).map((action) => action.label),
    ).toEqual(['Reject', 'Approve'])

    expect(
      getLoanDetailActions(
        {
          ...baseLoan,
          status: 'APPROVED_PENDING_DISBURSAL',
        },
        ['SYSTEM_ADMIN'],
      ).map((action) => action.label),
    ).toEqual(['Initiate Disbursal'])
  })

  it('describes activity recency and location cleanly', () => {
    expect(describeLastUpdated(baseLoan)).toContain('Created')
    expect(formatBorrowerLocation('Pune', 'Maharashtra')).toBe('Pune, Maharashtra')
    expect(formatBorrowerLocation('', '')).toBe('Not provided')
  })

  it('formats month-year values and document badges', () => {
    expect(formatMonthYearLabel('2026-05-01')).toBe('May 2026')
    expect(formatMonthYearLabel(null)).toBe('Not scheduled')
    expect(loanDocumentStatusVariant('VERIFIED')).toBe('success')
    expect(loanDocumentStatusVariant('REJECTED')).toBe('destructive')
    expect(loanDocumentStatusLabel('NOT_REQUIRED')).toBe('NOT REQUIRED')
  })
})
