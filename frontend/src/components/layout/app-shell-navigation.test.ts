import { buildShellNavigation } from './app-shell-navigation'

describe('buildShellNavigation', () => {
  it('returns tenant navigation for LSP users', () => {
    const navigation = buildShellNavigation({
      username: 'tenant.user',
      roles: ['LSP_UI_READ'],
      primaryRole: 'LSP_UI_READ',
      scope: 'Tenant A',
      lspId: 'lsp-1',
      lspName: 'Tenant A',
      application: 'LMS',
      activeProfiles: [],
      correlationId: null,
    })

    expect(navigation.primary.map((item) => item.to)).toEqual(['/home', '/my-loans'])
    expect(navigation.reports).toHaveLength(0)
  })

  it('exposes admin reports only to system admins', () => {
    const adminNavigation = buildShellNavigation({
      username: 'admin',
      roles: ['SYSTEM_ADMIN'],
      primaryRole: 'SYSTEM_ADMIN',
      scope: 'All LSPs',
      lspId: null,
      lspName: null,
      application: 'LMS',
      activeProfiles: [],
      correlationId: null,
    })

    const opsNavigation = buildShellNavigation({
      username: 'ops',
      roles: ['OPS_USER'],
      primaryRole: 'OPS_USER',
      scope: 'Operations',
      lspId: null,
      lspName: null,
      application: 'LMS',
      activeProfiles: [],
      correlationId: null,
    })

    expect(adminNavigation.reports.map((item) => item.to)).toEqual(['/reports'])
    expect(opsNavigation.reports).toHaveLength(0)
  })
})
