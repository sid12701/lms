import {
  forwardRef,
  type ButtonHTMLAttributes,
  type HTMLAttributes,
  type InputHTMLAttributes,
  type LabelHTMLAttributes,
  type SelectHTMLAttributes,
} from 'react'
import { cn } from '@/lib/utils'

type BadgeVariant = 'default' | 'success' | 'warning' | 'destructive'
type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'destructive'
type ButtonSize = 'sm' | 'default'

const badgeStyles: Record<BadgeVariant, string> = {
  default: 'bg-[#e8ebf7] text-[#000666]',
  success: 'bg-[#dceee7] text-[#167a54]',
  warning: 'bg-[#f1e7d1] text-[#8a6412]',
  destructive: 'bg-[#f3dce0] text-[#9f2f3d]',
}

const buttonStyles: Record<ButtonVariant, string> = {
  primary:
    'bg-gradient-to-r from-[#000666] to-[#1a237e] text-white shadow-[0_10px_24px_rgba(0,6,102,0.18)] hover:-translate-y-0.5 hover:shadow-[0_14px_30px_rgba(0,6,102,0.22)]',
  secondary:
    'bg-[#eef1f8] text-[#000666] shadow-[inset_0_0_0_1px_rgba(0,6,102,0.05)] hover:bg-white hover:shadow-[0_8px_20px_rgba(0,6,102,0.08)]',
  ghost:
    'bg-transparent text-[#000666] hover:bg-[#eef1f8] hover:shadow-[inset_0_0_0_1px_rgba(0,6,102,0.04)]',
  destructive:
    'bg-[#f3dce0] text-[#9f2f3d] shadow-[inset_0_0_0_1px_rgba(178,58,72,0.08)] hover:bg-[#efd0d6]',
}

const buttonSizes: Record<ButtonSize, string> = {
  sm: 'h-9 px-3 text-xs',
  default: 'h-11 px-4 text-sm',
}

export function AdminSurface({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <section
      className={cn(
        'overflow-hidden rounded-lg bg-white/90 shadow-[0_18px_55px_rgba(0,6,102,0.08)]',
        className,
      )}
      {...props}
    />
  )
}

export function AdminHeader({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('grid gap-2 px-6 pb-4 pt-6', className)} {...props} />
}

export function AdminContent({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('grid gap-4 px-6 pb-6', className)} {...props} />
}

export function AdminEyebrow({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('text-[0.72rem] font-bold uppercase text-[#000666]', className)}
      {...props}
    />
  )
}

export function AdminTitle({ className, ...props }: HTMLAttributes<HTMLHeadingElement>) {
  return <h3 className={cn('text-2xl font-extrabold text-[#0f1729]', className)} {...props} />
}

export function AdminDescription({ className, ...props }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn('max-w-3xl text-sm leading-6 text-[#5e6680]', className)} {...props} />
}

export function AdminBadge({ className, variant = 'default', ...props }: HTMLAttributes<HTMLSpanElement> & { variant?: BadgeVariant }) {
  return (
    <span
      className={cn(
        'inline-flex min-h-7 max-w-full items-center rounded-full px-3 py-1 text-xs font-extrabold uppercase',
        badgeStyles[variant],
        className,
      )}
      {...props}
    />
  )
}

export function AdminButton({
  className,
  variant = 'primary',
  size = 'default',
  type = 'button',
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: ButtonSize }) {
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center rounded-md font-extrabold transition duration-200 disabled:pointer-events-none disabled:opacity-55',
        buttonStyles[variant],
        buttonSizes[size],
        className,
      )}
      type={type}
      {...props}
    />
  )
}

export const AdminInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      className={cn(
        'h-11 w-full min-w-0 rounded-md bg-[#eef1f8] px-3 text-sm font-medium text-[#0f1729] outline-none shadow-[inset_0_0_0_1px_rgba(0,6,102,0.04)] transition placeholder:text-[#8a92a8] focus:bg-white focus:shadow-[inset_0_0_0_2px_rgba(0,6,102,0.26),0_10px_22px_rgba(0,6,102,0.08)] disabled:cursor-not-allowed disabled:opacity-60',
        className,
      )}
      ref={ref}
      {...props}
    />
  ),
)

AdminInput.displayName = 'AdminInput'

export function AdminSelect({ className, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        'h-11 w-full min-w-0 rounded-md bg-[#eef1f8] px-3 text-sm font-medium text-[#0f1729] outline-none shadow-[inset_0_0_0_1px_rgba(0,6,102,0.04)] transition focus:bg-white focus:shadow-[inset_0_0_0_2px_rgba(0,6,102,0.26),0_10px_22px_rgba(0,6,102,0.08)] disabled:cursor-not-allowed disabled:opacity-60',
        className,
      )}
      {...props}
    />
  )
}

export function AdminField({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('grid min-w-0 gap-2', className)} {...props} />
}

export function AdminFieldLabel({ className, ...props }: LabelHTMLAttributes<HTMLLabelElement>) {
  return <label className={cn('text-xs font-extrabold uppercase text-[#5e6680]', className)} {...props} />
}

export function AdminEmptyState({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        'rounded-lg bg-[#eef1f8]/80 px-4 py-3 text-sm font-medium leading-6 text-[#5e6680] shadow-[inset_0_0_0_1px_rgba(0,6,102,0.04)]',
        className,
      )}
      {...props}
    />
  )
}
