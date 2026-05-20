import {
  formatBorrowerEmail,
  formatBorrowerMobile,
  formatBorrowerPan,
  formatPayloadJson,
} from './loan-application-formatters'

describe('loan-application-formatters', () => {
  it('masks borrower identifiers by default', () => {
    expect(formatBorrowerPan('ABCDE1234F')).toBe('ABC•••••4F')
    expect(formatBorrowerMobile('9876543210')).toBe('••••••3210')
    expect(formatBorrowerEmail('user@example.com')).toBe('u•••@example.com')
  })

  it('masks sensitive payload fields recursively', () => {
    const payload = JSON.stringify({
      borrowerPan: 'ABCDE1234F',
      borrowerMobile: '9876543210',
      borrowerEmail: 'user@example.com',
    })

    const visible = formatPayloadJson(payload)

    expect(visible).toContain('ABC•••••4F')
    expect(visible).toContain('••••••3210')
    expect(visible).toContain('u•••@example.com')
  })
})
