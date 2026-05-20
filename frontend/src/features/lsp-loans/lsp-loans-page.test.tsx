import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { LoanApplicationDetailRecord, LoanApplicationRecord } from '../api/lms-api'
import { useAuth } from '../auth/auth-context'
import {
  getExternalLspLoanApplication,
  invalidateExternalLspLoanApplication,
  listExternalLspLoanApplications,
  listExternalLspLoanInvalidationReasons,
} from '../api/loan-applications-api'
import { LspLoansPage } from './lsp-loans-page'

vi.mock('../auth/auth-context', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../api/loan-applications-api', () => ({
  getExternalLspLoanApplication: vi.fn(),
  invalidateExternalLspLoanApplication: vi.fn(),
  listExternalLspLoanApplications: vi.fn(),
  listExternalLspLoanInvalidationReasons: vi.fn(),
}))

const mockedUseAuth = vi.mocked(useAuth)
const mockedGetExternalLspLoanApplication = vi.mocked(getExternalLspLoanApplication)
const mockedInvalidateExternalLspLoanApplication = vi.mocked(invalidateExternalLspLoanApplication)
const mockedListExternalLspLoanApplications = vi.mocked(listExternalLspLoanApplications)
const mockedListExternalLspLoanInvalidationReasons = vi.mocked(listExternalLspLoanInvalidationReasons)

const baseLoan: LoanApplicationRecord = {
  id: 'loan-1',
  borrowerId: 'borrower-1',
  borrowerFullName: 'Asha Rao',
  borrowerPan: 'ABCDE1234F',
  borrowerMobile: '9999999999',
  borrowerEmail: 'asha@example.com',
  borrowerDateOfBirth: '1992-02-10',
  borrowerCity: 'Mumbai',
  borrowerState: 'Maharashtra',
  borrowerEmploymentType: 'SALARIED',
  borrowerMonthlyIncome: 85000,
  lspId: 'lsp-1',
  lspCode: 'ABC',
  lspName: 'ABC Finance',
  productId: 'product-1',
  productCode: 'PL',
  productName: 'Prime Loan',
  externalLoanId: 'EXT-001',
  sourceChannel: 'PORTAL',
  requestedAmount: 450000,
  tenureMonths: 24,
  status: 'APPROVED_PENDING_DISBURSAL',
  assignedToUsername: null,
  assignedByUsername: null,
  assignedAt: null,
  createdAt: '2026-04-10T09:00:00Z',
}

const baseDetail: LoanApplicationDetailRecord = {
  ...baseLoan,
  updatedAt: '2026-04-10T10:00:00Z',
  invalidReasonCode: null,
  invalidReasonText: null,
  invalidatedByUsername: null,
  invalidatedAt: null,
  loanAccount: {
    id: 'account-1',
    accountNumber: 'LN-0001',
    status: 'PENDING_DISBURSEMENT',
    principalAmount: 450000,
    tenureMonths: 24,
    approvedAt: '2026-04-10T10:00:00Z',
    createdAt: '2026-04-10T10:00:00Z',
    closureReason: null,
    closedAt: null,
    closedByUsername: null,
    delinquency: {
      maxDaysPastDue: 0,
      bucket: 'CURRENT',
      overdueInstallmentCount: 0,
      overdueAmount: 0,
    },
    repaymentSchedule: {
      installmentCount: 24,
      installmentAmount: 21250,
      firstDueDate: '2026-05-10',
      finalDueDate: '2028-04-10',
    },
  },
  lastActivity: {
    activityType: 'STATUS_TRANSITION',
    actorUsername: 'ops.user',
    summary: 'Approved for disbursal',
    detail: null,
    correlationId: null,
    occurredAt: '2026-04-10T10:30:00Z',
  },
}

function buildAuthContext(roles: string[]) {
  return {
    user: {
      username: 'lsp.writer',
      roles,
      primaryRole: roles[0] ?? 'LSP_UI_READ',
      scope: 'ABC Finance',
      lspId: 'lsp-1',
      lspName: 'ABC Finance',
      application: 'LMS',
      activeProfiles: ['test'],
      correlationId: null,
    },
    mustChangePassword: false,
    login: vi.fn(),
    completePasswordChange: vi.fn(),
    logout: vi.fn(),
  }
}

describe('LspLoansPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockedListExternalLspLoanApplications.mockResolvedValue([baseLoan])
    mockedGetExternalLspLoanApplication.mockResolvedValue(baseDetail)
    mockedListExternalLspLoanInvalidationReasons.mockResolvedValue([
      { code: 'REASON_A', label: 'Reason A', requiresText: false },
      { code: 'REASON_B', label: 'Reason B', requiresText: false },
      { code: 'REASON_C', label: 'Reason C', requiresText: false },
      { code: 'OTHERS', label: 'Others', requiresText: true },
    ])
    mockedInvalidateExternalLspLoanApplication.mockResolvedValue({
      ...baseDetail,
      status: 'INVALID',
      invalidReasonCode: 'REASON_B',
      invalidReasonText: null,
      invalidatedByUsername: 'lsp.writer',
      invalidatedAt: '2026-04-10T11:00:00Z',
      loanAccount: {
        ...baseDetail.loanAccount!,
        status: 'INVALID',
      },
    })
  })

  it('hides the invalidation action for read-only users', async () => {
    mockedUseAuth.mockReturnValue(buildAuthContext(['LSP_UI_READ']))

    render(<LspLoansPage />)

    await screen.findByText('Asha Rao')
    await screen.findByText('Prime Loan')

    expect(screen.queryByRole('button', { name: /mark invalid/i })).not.toBeInTheDocument()
    expect(mockedListExternalLspLoanInvalidationReasons).not.toHaveBeenCalled()
  })

  it('shows the Others textarea only when that reason is selected', async () => {
    mockedUseAuth.mockReturnValue(buildAuthContext(['LSP_UI_WRITE']))

    render(<LspLoansPage />)

    await screen.findByRole('button', { name: /mark invalid/i })

    const reasonSelect = screen.getByLabelText('Reason')
    expect(screen.queryByLabelText(/reason details/i)).not.toBeInTheDocument()

    fireEvent.change(reasonSelect, { target: { value: 'OTHERS' } })

    expect(screen.getByLabelText(/reason details/i)).toBeInTheDocument()
  })

  it('submits invalidation with an idempotency key for write users', async () => {
    mockedUseAuth.mockReturnValue(buildAuthContext(['LSP_UI_WRITE']))
    const randomUuidSpy = vi
      .spyOn(globalThis.crypto, 'randomUUID')
      .mockReturnValue('123e4567-e89b-42d3-a456-426614174000')

    render(<LspLoansPage />)

    await screen.findByRole('button', { name: /mark invalid/i })

    fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'REASON_B' } })
    fireEvent.click(screen.getByRole('button', { name: /mark invalid/i }))

    await waitFor(() => {
      expect(mockedInvalidateExternalLspLoanApplication).toHaveBeenCalledWith(
        'loan-1',
        {
          reasonCode: 'REASON_B',
          reasonText: undefined,
        },
        '123e4567-e89b-42d3-a456-426614174000',
      )
    })

    expect(await screen.findByText(/lsp\.writer \|/i)).toBeInTheDocument()
    randomUuidSpy.mockRestore()
  })
})
