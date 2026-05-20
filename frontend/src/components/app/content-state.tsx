import type { ReactNode } from 'react'
import { AlertCircle, PackageOpen } from 'lucide-react'
import { BlueLoader } from '@/components/app/blue-loader'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'

type ContentStateTone = 'default' | 'error' | 'loading'

type ContentStateProps = {
  title: string
  description: ReactNode
  tone?: ContentStateTone
  className?: string
}

const toneConfig: Record<
  Exclude<ContentStateTone, 'loading'>,
  { icon: typeof PackageOpen; iconClassName: string }
> = {
  default: {
    icon: PackageOpen,
    iconClassName: 'text-muted-foreground',
  },
  error: {
    icon: AlertCircle,
    iconClassName: 'text-destructive',
  },
}

export function ContentState({
  title,
  description,
  tone = 'default',
  className,
}: ContentStateProps) {
  if (tone === 'loading') {
    return <BlueLoader title={title} description={description} className={className} />
  }

  const { icon: Icon, iconClassName } = toneConfig[tone]

  return (
    <Card className={cn('border-dashed bg-card/70', className)}>
      <CardHeader className="pb-3">
        <div className="flex items-center gap-3">
          <span className="inline-flex size-10 items-center justify-center rounded-full bg-muted/70">
            <Icon className={cn('size-5', iconClassName)} />
          </span>
          <CardTitle>{title}</CardTitle>
        </div>
      </CardHeader>
      <CardContent className="text-sm leading-6 text-muted-foreground">{description}</CardContent>
    </Card>
  )
}
