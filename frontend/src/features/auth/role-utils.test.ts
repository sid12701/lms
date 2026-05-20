import {
  canAccessReports,
  canManageProducts,
  isLspUiUser,
  resolveDefaultLandingPath,
} from './role-utils'

describe('role-utils', () => {
  it('detects LSP users from UI roles', () => {
    expect(isLspUiUser(['LSP_UI_READ'])).toBe(true)
    expect(isLspUiUser(['OPS_USER'])).toBe(false)
  })

  it('resolves default landing path from user role and password state', () => {
    expect(resolveDefaultLandingPath(null, false)).toBe('/login')
    expect(resolveDefaultLandingPath({ roles: ['SYSTEM_ADMIN'] }, true)).toBe('/change-password')
    expect(resolveDefaultLandingPath({ roles: ['LSP_UI_WRITE'] }, false)).toBe('/my-loans')
    expect(resolveDefaultLandingPath({ roles: ['SYSTEM_ADMIN'] }, false)).toBe('/home')
  })

  it('applies enterprise permissions consistently', () => {
    expect(canAccessReports(['SYSTEM_ADMIN'])).toBe(true)
    expect(canAccessReports(['OPS_USER'])).toBe(false)
    expect(canManageProducts(['PRODUCT_ADMIN'])).toBe(true)
  })
})
