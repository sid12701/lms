import type { AuthUser } from '../api/lms-api'

const LSP_UI_ROLES = ['LSP_UI_READ', 'LSP_UI_WRITE'] as const

function hasAnyRole(roles: readonly string[], expected: readonly string[]) {
  return expected.some((role) => roles.includes(role))
}

export function isLspUiUser(roles: readonly string[] = []) {
  return hasAnyRole(roles, LSP_UI_ROLES)
}

export function canAccessReports(roles: readonly string[] = []) {
  return roles.includes('SYSTEM_ADMIN')
}

export function canAccessAlerts(roles: readonly string[] = []) {
  return roles.includes('SYSTEM_ADMIN') || roles.includes('OPS_USER')
}

export function canManageLsps(roles: readonly string[] = []) {
  return roles.includes('SYSTEM_ADMIN')
}

export function canManageProducts(roles: readonly string[] = []) {
  return roles.includes('SYSTEM_ADMIN') || roles.includes('PRODUCT_ADMIN')
}

export function canManageUsers(roles: readonly string[] = []) {
  return roles.includes('SYSTEM_ADMIN')
}

export function resolveDefaultLandingPath(
  user: Pick<AuthUser, 'roles'> | null,
  mustChangePassword: boolean,
) {
  if (!user) {
    return '/login'
  }

  if (mustChangePassword) {
    return '/change-password'
  }

  return isLspUiUser(user.roles) ? '/my-loans' : '/home'
}
