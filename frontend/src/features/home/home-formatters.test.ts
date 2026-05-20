import { formatCompactCurrency, formatCurrency } from './home-formatters'

describe('home-formatters', () => {
  it('formats large INR values into compact labels', () => {
    expect(formatCompactCurrency(12500000)).toBe('₹1.25 Cr')
    expect(formatCompactCurrency(250000)).toBe('₹2.5L')
  })

  it('formats full INR values for detail views', () => {
    expect(formatCurrency(250000)).toContain('₹')
    expect(formatCurrency(250000)).toContain('2,50,000')
  })
})
