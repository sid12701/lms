import type { ReactNode } from 'react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

type PageSectionProps = {
  eyebrow?: string
  title?: string
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  headerClassName?: string
  contentClassName?: string
}

export function PageSection({
  eyebrow,
  title,
  description,
  actions,
  children,
  className,
  headerClassName,
  contentClassName,
}: PageSectionProps) {
  const hasHeader = eyebrow || title || description || actions

  return (
    <Card className={cn('overflow-hidden border-border/50 bg-card/95', className)}>
      {hasHeader ? (
        <CardHeader className={cn('gap-4 border-b border-border/70 pb-4', headerClassName)}>
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div className="space-y-2">
              {eyebrow ? (
                <p className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-muted-foreground">
                  {eyebrow}
                </p>
              ) : null}
              {title ? <CardTitle className="text-lg text-primary">{title}</CardTitle> : null}
              {description ? <CardDescription>{description}</CardDescription> : null}
            </div>
            {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
          </div>
        </CardHeader>
      ) : null}
      <CardContent className={cn('pt-6', contentClassName)}>{children}</CardContent>
    </Card>
  )
}
