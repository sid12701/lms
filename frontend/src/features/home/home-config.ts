import {
  Building2,
  FileText,
  NotebookPen,
  Settings2,
  Users,
  type LucideIcon,
} from 'lucide-react'
import type { AuthUser } from '@/features/api/lms-api'
import {
  canAccessReports,
  canManageLsps,
  canManageProducts,
  canManageUsers,
  isLspUiUser,
} from '@/features/auth/role-utils'

export type HomeLink = {
  to: string
  title: string
  description: string
  icon: LucideIcon
}

export function buildHomeLinks(user: AuthUser | null): HomeLink[] {
  const roles = user?.roles ?? []

  if (isLspUiUser(roles)) {
    return [
      {
        to: '/my-loans',
        title: 'My Loans',
        description: 'Review only the loans mapped to your LSP and export tenant-scoped data.',
        icon: NotebookPen,
      },
    ]
  }

  return [
    {
      to: '/loan-applications',
      title: 'Loan applications',
      description: 'Browse all applications across LSPs and use the core operational filters.',
      icon: NotebookPen,
    },
    ...(canManageLsps(roles)
      ? [
          {
            to: '/lsps',
            title: 'LSPs',
            description:
              'Inspect tenant-level summary information, sanctioned users, and portfolio totals.',
            icon: Building2,
          },
        ]
      : []),
    ...(canAccessReports(roles)
      ? [
          {
            to: '/reports',
            title: 'Reports',
            description: 'Generate and track MIS exports for internal portfolio reporting.',
            icon: FileText,
          },
        ]
      : []),
    ...(canManageProducts(roles)
      ? [
          {
            to: '/products',
            title: 'Loan products',
            description: 'Manage product definitions and operational loan configuration.',
            icon: Settings2,
          },
        ]
      : []),
    ...(canManageUsers(roles)
      ? [
          {
            to: '/users',
            title: 'Users',
            description: 'Administer platform users and access controls.',
            icon: Users,
          },
        ]
      : []),
  ]
}
