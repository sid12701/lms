import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { AppShellSidebar } from './app-shell-sidebar'

const adminUser = {
  username: 'admin',
  roles: ['SYSTEM_ADMIN'],
  primaryRole: 'SYSTEM_ADMIN',
  scope: 'All LSPs',
  lspId: null,
  lspName: null,
  application: 'LMS',
  activeProfiles: [],
  correlationId: null,
}

const lspUser = {
  username: 'tenant.user',
  roles: ['LSP_UI_READ'],
  primaryRole: 'LSP_UI_READ',
  scope: 'Tenant A',
  lspId: 'lsp-1',
  lspName: 'Tenant A',
  application: 'LMS',
  activeProfiles: [],
  correlationId: null,
}

describe('AppShellSidebar', () => {
  it('shows the reports group for admins', () => {
    render(
      <MemoryRouter>
        <AppShellSidebar
          user={adminUser}
          reportsOpen
          onToggleReports={() => {}}
          onLogout={() => {}}
          reportsActive={false}
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('button', { name: /reports/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'MIS' })).toBeInTheDocument()
  })

  it('hides the reports group for LSP users', () => {
    render(
      <MemoryRouter>
        <AppShellSidebar
          user={lspUser}
          reportsOpen
          onToggleReports={() => {}}
          onLogout={() => {}}
          reportsActive={false}
        />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('button', { name: /reports/i })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'My Loans' })).toBeInTheDocument()
  })
})
