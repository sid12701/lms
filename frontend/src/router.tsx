import type { ReactElement } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/app-shell'
import { useAuth } from './features/auth/auth-context'
import { ChangePasswordPage } from './features/auth/change-password-page'
import { LoginPage } from './features/auth/login-page'
import { LspAdminPage } from './features/admin/lsp-admin-page'
import { ApiClientsPage } from './features/api-clients/api-clients-page'
import { LoanApplicationsPage } from './features/loan-applications/loan-applications-page'
import { LspLoansPage } from './features/lsp-loans/lsp-loans-page'
import { ReportsPage } from './features/reports/reports-page'
import { UsersPage } from './features/users/users-page'
import { ProductConfigurationPage } from './features/products/product-configuration-page'

function isLspUiUser(roles: string[]) {
  return roles.includes('LSP_UI_READ') || roles.includes('LSP_UI_WRITE')
}

function canAccessReports(roles: string[]) {
  return roles.includes('SYSTEM_ADMIN')
}

function defaultLandingPath(user: { roles: string[] } | null, mustChangePassword: boolean) {
  if (!user) {
    return '/login'
  }

  if (mustChangePassword) {
    return '/change-password'
  }

  return isLspUiUser(user.roles) ? '/my-loans' : '/loan-applications'
}

function ProtectedRoute({ children }: { children: ReactElement }) {
  const { user, mustChangePassword } = useAuth()

  if (!user) {
    return <Navigate to="/login" replace />
  }

  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />
  }

  return children
}

function PasswordChangeRoute({ children }: { children: ReactElement }) {
  const { user, mustChangePassword } = useAuth()

  if (!user) {
    return <Navigate to="/login" replace />
  }

  if (!mustChangePassword) {
    return <Navigate to={defaultLandingPath(user, false)} replace />
  }

  return children
}

function LoginRoute() {
  const { user, mustChangePassword } = useAuth()

  if (user) {
    return <Navigate to={defaultLandingPath(user, mustChangePassword)} replace />
  }

  return <LoginPage />
}

function InternalOnlyRoute({ children }: { children: ReactElement }) {
  const { user } = useAuth()

  if (user && isLspUiUser(user.roles)) {
    return <Navigate to="/my-loans" replace />
  }

  return children
}

function LspOnlyRoute({ children }: { children: ReactElement }) {
  const { user } = useAuth()

  if (user && !isLspUiUser(user.roles)) {
    return <Navigate to="/loan-applications" replace />
  }

  return children
}

function ReportsOnlyRoute({ children }: { children: ReactElement }) {
  const { user } = useAuth()

  if (user && !canAccessReports(user.roles)) {
    return <Navigate to={defaultLandingPath(user, false)} replace />
  }

  return children
}

export function AppRouter() {
  const { user, mustChangePassword } = useAuth()

  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route
        path="/change-password"
        element={
          <PasswordChangeRoute>
            <ChangePasswordPage />
          </PasswordChangeRoute>
        }
      />
      <Route path="/" element={<Navigate to={defaultLandingPath(user, mustChangePassword)} replace />} />
      <Route
        path="/*"
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        <Route
          path="dashboard"
          element={
            <InternalOnlyRoute>
              <Navigate to="/loan-applications" replace />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="my-loans"
          element={
            <LspOnlyRoute>
              <LspLoansPage />
            </LspOnlyRoute>
          }
        />
        <Route
          path="lsps"
          element={
            <InternalOnlyRoute>
              <LspAdminPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="api-clients"
          element={
            <InternalOnlyRoute>
              <ApiClientsPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="users"
          element={
            <InternalOnlyRoute>
              <UsersPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="products"
          element={
            <InternalOnlyRoute>
              <ProductConfigurationPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="loan-applications"
          element={
            <InternalOnlyRoute>
              <LoanApplicationsPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="reports"
          element={
            <InternalOnlyRoute>
              <ReportsOnlyRoute>
                <ReportsPage />
              </ReportsOnlyRoute>
            </InternalOnlyRoute>
          }
        />
      </Route>
    </Routes>
  )
}
