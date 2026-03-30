import { forwardRef } from 'react'
import { cn } from '../../lib/cn'

export const Input = forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => {
    return <input className={cn('ui-input', className)} ref={ref} {...props} />
  },
)

Input.displayName = 'Input'
