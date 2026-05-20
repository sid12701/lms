import type { ReactElement } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/app-shell'
import { useAuth } from './features/auth/auth-context'
import { AlertsPage } from './features/alerts/alerts-page'
import { ChangePasswordPage } from './features/auth/change-password-page'
import { LoginPage } from './features/auth/login-page'
import { BorrowerDetailPage } from './features/borrowers/borrower-detail-page'
import { LspAdminPage } from './features/admin/lsp-admin-page'
import { ApiClientsPage } from './features/api-clients/api-clients-page'
import {
  canAccessAlerts,
  canAccessReports,
  isLspUiUser,
  resolveDefaultLandingPath,
} from './features/auth/role-utils'
import { HomePage } from './features/home/home-page'
import { LoanApplicationDetailPage } from './features/loan-applications/loan-application-detail-page'
import { LoanApplicationsPage } from './features/loan-applications/loan-applications-page'
import { LspLoansPage } from './features/lsp-loans/lsp-loans-page'
import { ReportsPage } from './features/reports/reports-page'
import { UsersPage } from './features/users/users-page'
import { ProductConfigurationPage } from './features/products/product-configuration-page'

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
    return <Navigate to={resolveDefaultLandingPath(user, false)} replace />
  }

  return children
}

function LoginRoute() {
  const { user, mustChangePassword } = useAuth()

  if (user) {
    return <Navigate to={resolveDefaultLandingPath(user, mustChangePassword)} replace />
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
    return <Navigate to={resolveDefaultLandingPath(user, false)} replace />
  }

  return children
}

function AlertsOnlyRoute({ children }: { children: ReactElement }) {
  const { user } = useAuth()

  if (user && !canAccessAlerts(user.roles)) {
    return <Navigate to={resolveDefaultLandingPath(user, false)} replace />
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
      <Route path="/" element={<Navigate to={resolveDefaultLandingPath(user, mustChangePassword)} replace />} />
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
            <Navigate to="/home" replace />
          }
        />
        <Route path="home" element={<HomePage />} />
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
          path="loan-applications/:id"
          element={
            <InternalOnlyRoute>
              <LoanApplicationDetailPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="borrowers/:id"
          element={
            <InternalOnlyRoute>
              <BorrowerDetailPage />
            </InternalOnlyRoute>
          }
        />
        <Route
          path="alerts"
          element={
            <InternalOnlyRoute>
              <AlertsOnlyRoute>
                <AlertsPage />
              </AlertsOnlyRoute>
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
