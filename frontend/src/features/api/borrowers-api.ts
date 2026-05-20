import { requestJson } from './http-client'

export interface BorrowerLoanRecord {
  loanAccountId: string | null
  applicationId: string | null
  accountNumber: string | null
  lspId: string | null
  lspCode: string | null
  lspName: string | null
  loanProductCode: string | null
  status: string | null
  principalAmount: number | null
  tenureMonths: number
  approvedAt: string | null
  disbursedAt: string | null
  closureReason: string | null
  closedAt: string | null
  closedByUsername: string | null
  createdAt: string | null
}

export interface BorrowerDetailRecord {
  id: string
  fullName: string
  pan: string
  mobile: string
  email: string | null
  dateOfBirth: string | null
  gender: string | null
  maritalStatus: string | null
  fatherName: string | null
  aadharNumberMasked: string | null
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  addressZipCode: string | null
  spouseName: string | null
  employmentType: string | null
  organizationName: string | null
  employeeId: string | null
  employmentCity: string | null
  employmentState: string | null
  employmentZip: string | null
  monthlyIncome: number | null
  annualIncome: number | null
  bankAccountNumberMasked: string | null
  bankName: string | null
  ifscCode: string | null
  accountHolderName: string | null
  referencePersonName: string | null
  referencePersonNumber: string | null
  visibleLspIds: string[]
  loans: BorrowerLoanRecord[]
}

export function getBorrowerDetail(borrowerId: string) {
  return requestJson<BorrowerDetailRecord>(
    `/api/v1/internal/admin/borrowers/${borrowerId}`,
  )
}
