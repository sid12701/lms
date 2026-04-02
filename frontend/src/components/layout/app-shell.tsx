import {
  BellRing,
  KeyRound,
  LayoutDashboard,
  NotebookPen,
  FileText,
  LogOut,
  Settings2,
  ShieldCheck,
  Building2,
  Users,
} from 'lucide-react'
import { NavLink, Outlet } from 'react-router-dom'
import { Button } from '../ui/button'
import { Badge } from '../ui/badge'
import { useAuth } from '../../features/auth/auth-context'
import { cn } from '../../lib/cn'

const internalNavigation = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/lsps', label: 'LSP Administration', icon: Building2 },
  { to: '/api-clients', label: 'API Clients', icon: KeyRound },
  { to: '/users', label: 'User Administration', icon: Users },
  { to: '/products', label: 'Product Configuration', icon: Settings2 },
  { to: '/loan-applications', label: 'Loan Intake', icon: NotebookPen },
  { to: '/dashboard', label: 'Alerts & Monitoring', icon: BellRing },
]

const lspNavigation = [
  { to: '/my-loans', label: 'My Loans', icon: NotebookPen },
]

export function AppShell() {
  const { user, logout } = useAuth()
  const isLspUiUser = user?.roles.some((role) => role === 'LSP_UI_READ' || role === 'LSP_UI_WRITE') ?? false
  const canAccessReports = user?.roles.includes('SYSTEM_ADMIN') ?? false
  const navigation = isLspUiUser
    ? lspNavigation
    : canAccessReports
      ? [...internalNavigation, { to: '/reports', label: 'Reports', icon: FileText }]
      : internalNavigation

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-block">
          <div className="section-eyebrow">Bhawana Capital</div>
          <h1>Sovereign Ledger</h1>
          <p>{isLspUiUser ? 'Tenant-scoped loan visibility and reporting.' : 'Multi-tenant loan operations and lender controls.'}</p>
        </div>

        <nav className="sidebar-nav" aria-label="Primary">
          {navigation.map((item) => {
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
            <h2>{isLspUiUser ? 'Loan Visibility and Reports' : 'Admin and Product Control'}</h2>
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
