import { Slot } from '@radix-ui/react-slot'
import { cva, type VariantProps } from 'class-variance-authority'
import { forwardRef } from 'react'
import { cn } from '../../lib/cn'

export const buttonVariants = cva('ui-button', {
  variants: {
    variant: {
      default: 'ui-button--primary',
      primary: 'ui-button--primary',
      secondary: 'ui-button--secondary',
      outline: 'ui-button--outline',
      ghost: 'ui-button--ghost',
      destructive: 'ui-button--destructive',
    },
    size: {
      sm: 'ui-button--sm',
      default: 'ui-button--default',
      lg: 'ui-button--lg',
      icon: 'ui-button--icon',
    },
  },
  defaultVariants: { variant: 'primary', size: 'default' },
})

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> &
  VariantProps<typeof buttonVariants> & {
    asChild?: boolean
  }

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Component = asChild ? Slot : 'button'
    return (
      <Component
        className={cn(buttonVariants({ variant, size }), className)}
        ref={ref}
        {...props}
      />
    )
  },
)

Button.displayName = 'Button'
