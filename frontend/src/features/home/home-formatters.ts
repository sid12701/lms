const compactNumberFormatter = new Intl.NumberFormat('en-IN', {
  maximumFractionDigits: 0,
})

const currencyFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  maximumFractionDigits: 0,
})

export function formatCompactCurrency(value: number) {
  if (value >= 1e7) {
    return `\u20B9${(value / 1e7).toFixed(2)} Cr`
  }

  if (value >= 1e5) {
    return `\u20B9${(value / 1e5).toFixed(1)}L`
  }

  return `\u20B9${compactNumberFormatter.format(value)}`
}

export function formatCurrency(value: number) {
  return currencyFormatter.format(value)
}
