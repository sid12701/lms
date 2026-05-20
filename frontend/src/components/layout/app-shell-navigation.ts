import {
  Building2,
  FileText,
  House,
  NotebookPen,
  Settings2,
  TriangleAlert,
  Users,
  type LucideIcon,
} from 'lucide-react'
import type { AuthUser } from '@/features/api/lms-api'
import {
  canAccessAlerts,
  canAccessReports,
  canManageLsps,
  canManageProducts,
  canManageUsers,
  isLspUiUser,
} from '@/features/auth/role-utils'

export type ShellNavigationItem = {
  label: string
  to: string
  icon: LucideIcon
}

export type ShellNavigation = {
  primary: ShellNavigationItem[]
  reports: ShellNavigationItem[]
}

const lspNavigation: ShellNavigationItem[] = [
  { to: '/home', label: 'Home', icon: House },
  { to: '/my-loans', label: 'My Loans', icon: NotebookPen },
]

export function buildShellNavigation(user: AuthUser | null): ShellNavigation {
  const roles = user?.roles ?? []

  if (isLspUiUser(roles)) {
    return {
      primary: lspNavigation,
      reports: [],
    }
  }

  return {
    primary: [
      { to: '/home', label: 'Home', icon: House },
      { to: '/loan-applications', label: 'Loan applications', icon: NotebookPen },
      ...(canManageLsps(roles) ? [{ to: '/lsps', label: 'LSPs', icon: Building2 }] : []),
      ...(canManageProducts(roles)
        ? [{ to: '/products', label: 'Loan products', icon: Settings2 }]
        : []),
      ...(canManageUsers(roles) ? [{ to: '/users', label: 'Users', icon: Users }] : []),
      ...(canAccessAlerts(roles) ? [{ to: '/alerts', label: 'Alerts', icon: TriangleAlert }] : []),
    ],
    reports: canAccessReports(roles)
      ? [{ to: '/reports', label: 'MIS', icon: FileText }]
      : [],
  }
}
