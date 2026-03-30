import type { ReactElement } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/app-shell'
import { useAuth } from './features/auth/auth-context'
import { ChangePasswordPage } from './features/auth/change-password-page'
import { DashboardPage } from './features/dashboard/dashboard-page'
import { LoginPage } from './features/auth/login-page'
import { LspAdminPage } from './features/admin/lsp-admin-page'
import { ApiClientsPage } from './features/api-clients/api-clients-page'
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
    return <Navigate to="/dashboard" replace />
  }

  return children
}

function LoginRoute() {
  const { user, mustChangePassword } = useAuth()

  if (user) {
    return <Navigate to={mustChangePassword ? '/change-password' : '/dashboard'} replace />
  }

  return <LoginPage />
}

export function AppRouter() {
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
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route
        path="/*"
        element={
          <ProtectedRoute>
            <AppShell />
          </ProtectedRoute>
        }
      >
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="lsps" element={<LspAdminPage />} />
        <Route path="api-clients" element={<ApiClientsPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="products" element={<ProductConfigurationPage />} />
      </Route>
    </Routes>
  )
}
