import { Bell, CircleHelp, Search, ShieldCheck } from 'lucide-react'
import type { AuthUser } from '@/features/api/lms-api'
import { isLspUiUser } from '@/features/auth/role-utils'
import { Badge } from '@/components/ui/badge'

type AppShellHeaderProps = {
  user: AuthUser | null
}

function buildAvatarInitials(displayName: string) {
  return displayName
    .split(/[.\s_-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
}

export function AppShellHeader({ user }: AppShellHeaderProps) {
  const displayName = user?.username ?? 'Operator'
  const avatarInitials = buildAvatarInitials(displayName) || 'OP'
  const lspUser = isLspUiUser(user?.roles)

  return (
    <header className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
      <label className="relative block w-full lg:max-w-[26rem]">
        <Search className="pointer-events-none absolute top-1/2 left-4 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          aria-label="Search ledger"
          className="h-12 w-full rounded-xl border-0 bg-[#e9ebef] pr-4 pl-11 text-sm text-foreground shadow-[inset_0_0_0_1px_rgba(0,6,102,0.03)] outline-none transition-[background-color,box-shadow] placeholder:text-muted-foreground focus:bg-white focus:shadow-[0_0_0_3px_rgba(0,6,102,0.08)]"
          placeholder="Search ledger, loans, or clients..."
          type="search"
        />
      </label>

      <div className="flex flex-wrap items-center gap-3 lg:justify-end">
        <div className="hidden items-center gap-2 border-r border-border/70 pr-3 xl:flex">
          <Badge variant="success">
            <ShieldCheck className="size-3.5" />
            JWT session
          </Badge>
          <Badge>{user?.activeProfiles?.join(', ') || 'default'}</Badge>
        </div>

        <button
          className="inline-flex size-9 items-center justify-center rounded-full text-primary transition-colors hover:bg-[#eef1f8]"
          type="button"
          aria-label="Notifications"
        >
          <Bell className="size-4" />
        </button>
        <button
          className="inline-flex size-9 items-center justify-center rounded-full text-primary transition-colors hover:bg-[#eef1f8]"
          type="button"
          aria-label="Help"
        >
          <CircleHelp className="size-4" />
        </button>

        <div className="flex items-center gap-3 border-l border-border/70 pl-3">
          <div className="text-right">
            <p className="text-[0.7rem] font-semibold uppercase tracking-[0.12em] text-primary">
              {lspUser ? 'LSP Workspace' : 'Institutional Admin'}
            </p>
            <p className="text-xs text-muted-foreground">{user?.application ?? 'Bhawana HQ'}</p>
          </div>
          <div className="inline-flex size-10 items-center justify-center rounded-2xl bg-[linear-gradient(135deg,var(--primary),#1a237e)] text-xs font-bold text-primary-foreground shadow-[0_10px_24px_rgba(0,6,102,0.16)]">
            {avatarInitials}
          </div>
        </div>
      </div>
    </header>
  )
}
