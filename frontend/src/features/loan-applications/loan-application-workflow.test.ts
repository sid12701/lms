import {
  getVisibleTransitionActions,
  loanAuditActionLabel,
  loanDelinquencyBucketVariant,
  loanStatusLabel,
} from './loan-application-workflow'

describe('loan-application-workflow', () => {
  it('maps statuses to readable labels', () => {
    expect(loanStatusLabel('AWAITING_APPROVAL')).toBe('Awaiting approval')
    expect(loanStatusLabel('INVALID')).toBe('Invalid')
    expect(loanAuditActionLabel('INVALIDATED')).toBe('Invalidated')
  })

  it('limits transition actions by role', () => {
    expect(getVisibleTransitionActions('INITIALIZED', ['OPS_USER'])).toHaveLength(1)
    expect(getVisibleTransitionActions('AWAITING_APPROVAL', ['OPS_USER'])).toHaveLength(0)
    expect(getVisibleTransitionActions('AWAITING_APPROVAL', ['SYSTEM_ADMIN'])).toHaveLength(2)
  })

  it('treats severe delinquency buckets as destructive', () => {
    expect(loanDelinquencyBucketVariant('DPD_90_PLUS')).toBe('destructive')
    expect(loanDelinquencyBucketVariant('CURRENT')).toBe('success')
  })
})
