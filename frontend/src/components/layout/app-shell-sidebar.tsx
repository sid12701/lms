import { ChevronDown, ChevronRight, FileText, LogOut } from 'lucide-react'
import { NavLink } from 'react-router-dom'
import type { AuthUser } from '@/features/api/lms-api'
import { isLspUiUser } from '@/features/auth/role-utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { buildShellNavigation } from './app-shell-navigation'

type AppShellSidebarProps = {
  user: AuthUser | null
  reportsOpen: boolean
  onToggleReports: () => void
  onLogout: () => void
  reportsActive: boolean
}

function navLinkClassName(isActive: boolean, isSubnav = false) {
  return cn(
    'group flex items-center gap-3 rounded-xl px-4 py-3 text-sm font-medium text-sidebar-foreground transition-colors hover:bg-sidebar-accent hover:text-primary',
    isSubnav && 'pl-11 text-[0.82rem]',
    isActive && 'bg-card text-primary shadow-[0_10px_24px_rgba(25,28,29,0.06)]',
  )
}

export function AppShellSidebar({
  user,
  reportsOpen,
  onToggleReports,
  onLogout,
  reportsActive,
}: AppShellSidebarProps) {
  const navigation = buildShellNavigation(user)
  const lspUser = isLspUiUser(user?.roles)

  return (
    <aside className="border-b border-sidebar-border bg-sidebar/95 px-4 py-5 backdrop-blur lg:sticky lg:top-0 lg:h-screen lg:overflow-y-auto lg:border-r lg:border-b-0 lg:px-5 lg:py-6">
      <div className="space-y-1 px-2 pb-4">
        <p className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-sidebar-foreground">
          Bhawana Capital
        </p>
        <h1 className="font-heading text-xl font-bold tracking-[-0.02em] text-primary">
          Sovereign Ledger
        </h1>
        <p className="max-w-xs text-xs leading-5 text-sidebar-foreground">
          {lspUser
            ? 'Tenant-scoped loan visibility and reporting.'
            : 'Multi-tenant loan operations and lender controls.'}
        </p>
      </div>

      <nav className="flex flex-col gap-1 py-3" aria-label="Primary">
        {navigation.primary.map((item) => {
          const Icon = item.icon

          return (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => navLinkClassName(isActive)}
            >
              {({ isActive }) => (
                <>
                  <Icon
                    className={cn(
                      'size-[1.05rem] shrink-0 opacity-70 transition-opacity',
                      isActive && 'opacity-100',
                    )}
                  />
                  <span>{item.label}</span>
                </>
              )}
            </NavLink>
          )
        })}

        {navigation.reports.length > 0 ? (
          <div className="flex flex-col gap-1">
            <button
              type="button"
              className={navLinkClassName(reportsActive)}
              onClick={onToggleReports}
            >
              <FileText className="size-[1.05rem] shrink-0 opacity-70" />
              <span className="flex-1 text-left">Reports</span>
              {reportsOpen ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
            </button>

            {reportsOpen ? (
              <div className="flex flex-col gap-1">
                {navigation.reports.map((item) => (
                  <NavLink
                    key={item.to}
                    to={item.to}
                    className={({ isActive }) => navLinkClassName(isActive, true)}
                  >
                    {item.label}
                  </NavLink>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}
      </nav>

      <div className="mt-6 border-t border-sidebar-border px-2 pt-4 lg:mt-auto">
        <p className="text-[0.68rem] font-semibold uppercase tracking-[0.18em] text-sidebar-foreground">
          Actor Context
        </p>
        <div className="mt-2 space-y-1">
          <h2 className="text-sm font-semibold text-foreground">{user?.username ?? 'Operator'}</h2>
          <p className="text-xs text-sidebar-foreground">{user?.application ?? 'Bhawana HQ'}</p>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          {user?.primaryRole ? <Badge>{user.primaryRole}</Badge> : null}
          {user?.scope ? <Badge variant="warning">{user.scope}</Badge> : null}
        </div>
        <Button className="mt-4 w-full" variant="ghost" onClick={onLogout}>
          <LogOut className="size-4" />
          Sign out
        </Button>
      </div>
    </aside>
  )
}
