import {
  ChevronDown,
  ChevronRight,
  FileText,
  LogOut,
  Settings2,
  ShieldCheck,
  Building2,
  NotebookPen,
  Users,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { Button } from '../ui/button'
import { Badge } from '../ui/badge'
import { useAuth } from '../../features/auth/auth-context'
import { cn } from '../../lib/cn'

const lspNavigation = [
  { to: '/my-loans', label: 'My Loans', icon: NotebookPen },
]

export function AppShell() {
  const { user, logout } = useAuth()
  const location = useLocation()
  const isLspUiUser = user?.roles.some((role) => role === 'LSP_UI_READ' || role === 'LSP_UI_WRITE') ?? false
  const canAccessReports = user?.roles.includes('SYSTEM_ADMIN') ?? false
  const canManageLsps = user?.roles.includes('SYSTEM_ADMIN') ?? false
  const canManageProducts =
    user?.roles.includes('SYSTEM_ADMIN') || user?.roles.includes('PRODUCT_ADMIN') || false
  const canManageUsers = user?.roles.includes('SYSTEM_ADMIN') ?? false
  const [reportsOpen, setReportsOpen] = useState(location.pathname.startsWith('/reports'))

  const internalNavigation = useMemo(
    () =>
      [
        { to: '/loan-applications', label: 'Loan applications', icon: NotebookPen, visible: true },
        { to: '/lsps', label: 'LSPs', icon: Building2, visible: canManageLsps },
        { to: '/products', label: 'Loan products', icon: Settings2, visible: canManageProducts },
        { to: '/users', label: 'Users', icon: Users, visible: canManageUsers },
      ].filter((item) => item.visible),
    [canManageLsps, canManageProducts, canManageUsers],
  )

  useEffect(() => {
    if (location.pathname.startsWith('/reports')) {
      setReportsOpen(true)
    }
  }, [location.pathname])

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <div className="section-eyebrow">Bhawana Capital</div>
          <h1>Sovereign Ledger</h1>
          <p>{isLspUiUser ? 'Tenant-scoped loan visibility and reporting.' : 'Multi-tenant loan operations and lender controls.'}</p>
        </div>

        <nav className="sidebar-nav" aria-label="Primary">
          {(isLspUiUser ? lspNavigation : internalNavigation).map((item) => {
            const Icon = item.icon
            return (
              <NavLink
                key={item.label}
                to={item.to}
                className={({ isActive }) =>
                  cn('sidebar-link', isActive && 'sidebar-link--active')
                }
              >
                <Icon size={18} />
                <span>{item.label}</span>
              </NavLink>
            )
          })}
          {!isLspUiUser && canAccessReports ? (
            <div className="sidebar-group">
              <button
                type="button"
                className={cn('sidebar-link', location.pathname.startsWith('/reports') && 'sidebar-link--active')}
                onClick={() => setReportsOpen((current) => !current)}
              >
                <FileText size={18} />
                <span>Reports</span>
                {reportsOpen ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
              </button>
              {reportsOpen ? (
                <div className="sidebar-subnav">
                  <NavLink
                    to="/reports"
                    className={({ isActive }) =>
                      cn('sidebar-link', 'sidebar-link--subnav', isActive && 'sidebar-link--active')
                    }
                  >
                    <span>MIS</span>
                  </NavLink>
                </div>
              ) : null}
            </div>
          ) : null}
        </nav>

        <div className="sidebar-footer">
          <div className="section-eyebrow">Actor Context</div>
          <h3>{user?.username}</h3>
          <p>{user?.application}</p>
          <div className="inline-actions">
            <Badge>{user?.primaryRole}</Badge>
            <Badge variant="warning">{user?.scope}</Badge>
          </div>
          <Button className="sidebar-signout" variant="ghost" onClick={logout}>
            <LogOut size={16} />
            Sign out
          </Button>
        </div>
      </aside>

      <main className="content-area">
        <header className="content-header">
          <div>
            <div className="section-eyebrow">{isLspUiUser ? 'LSP Workspace' : 'Operations Console'}</div>
            <h2>{isLspUiUser ? 'Loan Visibility and Reports' : 'Loan operations, tenants, and reporting'}</h2>
          </div>
          <div className="header-meta">
            <Badge variant="success">
              <ShieldCheck size={14} />
              JWT session
            </Badge>
            <Badge>{user?.activeProfiles?.join(', ') || 'default'}</Badge>
          </div>
        </header>
        <Outlet />
      </main>
    </div>
  )
}
