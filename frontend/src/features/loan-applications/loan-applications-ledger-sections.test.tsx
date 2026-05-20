import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { LoanApplicationRecord } from '../api/lms-api'
import { LoanLedgerTableSection } from './loan-applications-ledger-sections'

const application: LoanApplicationRecord = {
  id: '12345678-90ab-cdef-1234-567890abcdef',
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
  sourceChannel: 'MOBILE_APP',
  requestedAmount: 450000,
  tenureMonths: 24,
  status: 'DISBURSED',
  assignedToUsername: 'ops.user',
  assignedByUsername: 'ops.admin',
  assignedAt: '2026-04-10T10:00:00Z',
  createdAt: '2026-04-10T09:00:00Z',
}

describe('LoanLedgerTableSection', () => {
  it('renders an application row with a working detail route', () => {
    render(
      <MemoryRouter>
        <LoanLedgerTableSection applications={[application]} loading={false} error="" />
      </MemoryRouter>,
    )

    expect(screen.getByText('Asha Rao')).toBeInTheDocument()
    expect(screen.getByText('Prime Loan')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /view details/i })).toHaveAttribute(
      'href',
      '/loan-applications/12345678-90ab-cdef-1234-567890abcdef',
    )
  })

  it('renders the empty-state copy when no applications are available', () => {
    render(
      <MemoryRouter>
        <LoanLedgerTableSection applications={[]} loading={false} error="" />
      </MemoryRouter>,
    )

    expect(
      screen.getByText(/No applications matched the current filters/i),
    ).toBeInTheDocument()
  })
})
