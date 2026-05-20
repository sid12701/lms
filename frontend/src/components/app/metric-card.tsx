import type { ReactNode } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { cn } from '@/lib/utils'

type MetricCardTone = 'default' | 'danger' | 'success'

type MetricCardProps = {
  label: string
  value: ReactNode
  detail?: ReactNode
  accent?: ReactNode
  tone?: MetricCardTone
  className?: string
}

const toneStyles: Record<MetricCardTone, string> = {
  default: 'text-primary',
  danger: 'text-destructive',
  success: 'text-[color:var(--color-success)]',
}

export function MetricCard({
  label,
  value,
  detail,
  accent,
  tone = 'default',
  className,
}: MetricCardProps) {
  return (
    <Card
      className={cn(
        'border-border/50 bg-card/95 shadow-[0_16px_36px_rgba(25,28,29,0.05)]',
        tone === 'danger' && 'border-destructive/20 bg-destructive/5',
        className,
      )}
    >
      <CardContent className="space-y-4 p-6">
        <div className="flex items-start justify-between gap-3">
          <p className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
            {label}
          </p>
          {accent}
        </div>
        <div className="space-y-2">
          <div className={cn('font-heading text-3xl font-bold tracking-[-0.03em]', toneStyles[tone])}>
            {value}
          </div>
          {detail ? <div className="text-sm font-medium text-muted-foreground">{detail}</div> : null}
        </div>
      </CardContent>
    </Card>
  )
}
