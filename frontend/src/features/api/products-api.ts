import { requestJson } from './http-client'
import type {
  LoanProductCreateResponse,
  LoanProductRecord,
  LoanProductStatus,
  ProductAuditEventRecord,
  ProductLspMappingRecord,
} from './lms-api'

export function listLoanProducts() {
  return requestJson<LoanProductRecord[]>('/api/v1/internal/admin/products')
}

export function listProductLspMappings() {
  return requestJson<ProductLspMappingRecord[]>('/api/v1/internal/admin/product-lsp-mappings')
}

export function listProductAuditEvents(productId: string) {
  return requestJson<ProductAuditEventRecord[]>(
    `/api/v1/internal/admin/products/${productId}/audit-events`,
  )
}

export function createLoanProduct(payload: {
  code: string
  name: string
  minPrincipal: number
  maxPrincipal: number
  interestRate: number
  processingFeeRate: number
  minTenureMonths: number
  maxTenureMonths: number
  status?: LoanProductStatus
}) {
  return requestJson<LoanProductCreateResponse>('/api/v1/internal/admin/products', {
    method: 'POST',
    body: JSON.stringify(payload),
  })
}

export function updateLoanProduct(
  productId: string,
  payload: {
    code: string
    name: string
    minPrincipal: number
    maxPrincipal: number
    interestRate: number
    processingFeeRate: number
    minTenureMonths: number
    maxTenureMonths: number
    status?: LoanProductStatus
  },
) {
  return requestJson<LoanProductRecord>(`/api/v1/internal/admin/products/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}

export function saveProductLspMappings(
  productId: string,
  payload: {
    lspIds: string[]
  },
) {
  return requestJson<void>(`/api/v1/internal/admin/product-lsp-mappings/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  })
}
