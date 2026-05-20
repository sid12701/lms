import { Link } from 'react-router-dom'
import { Card, CardHeader, CardTitle } from '@/components/ui/card'
import type { HomeLink } from './home-config'

export function HomeLinkCard({ to, title, description, icon: Icon }: HomeLink) {
  return (
    <Link to={to} className="block h-full">
      <Card className="h-full transition-[box-shadow,transform] hover:-translate-y-0.5 hover:shadow-[0_18px_36px_rgba(25,28,29,0.06)]">
        <CardHeader className="space-y-3">
          <div className="flex items-center gap-2">
            <span className="inline-flex size-9 items-center justify-center rounded-full bg-primary/8 text-primary">
              <Icon className="size-[1.05rem]" />
            </span>
            <span className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground">
              Workspace
            </span>
          </div>
          <div className="space-y-1">
            <CardTitle className="text-base">{title}</CardTitle>
            <p className="text-sm leading-6 text-muted-foreground">{description}</p>
          </div>
        </CardHeader>
      </Card>
    </Link>
  )
}
