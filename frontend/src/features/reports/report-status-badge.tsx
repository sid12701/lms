import type { ReactNode } from 'react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import type { ReportBadgeTone } from './reports-model'

type ReportStatusBadgeProps = {
  tone: ReportBadgeTone
  children: ReactNode
  className?: string
}

const toneClassNames: Record<ReportBadgeTone, string> = {
  default: 'border-0 bg-primary/10 text-primary',
  success: 'border-0 bg-emerald-100 text-emerald-800',
  warning: 'border-0 bg-amber-100 text-amber-800',
  danger: 'border-0 bg-destructive/10 text-destructive',
}

export function ReportStatusBadge({
  tone,
  children,
  className,
}: ReportStatusBadgeProps) {
  return (
    <Badge
      className={cn(
        'px-2.5 py-1 text-[0.62rem] tracking-[0.12em]',
        toneClassNames[tone],
        className,
      )}
    >
      {children}
    </Badge>
  )
}
