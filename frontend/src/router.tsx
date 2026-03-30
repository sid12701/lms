import type { ReactElement } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/app-shell'
import { useAuth } from './features/auth/auth-context'
import { DashboardPage } from './features/dashboard/dashboard-page'
import { LoginPage } from './features/auth/login-page'
import { LspAdminPage } from './features/admin/lsp-admin-page'
import { UsersPage } from './features/users/users-page'

function ProtectedRoute({ children }: { children: ReactElement }) {
  const { user } = useAuth()
  return user ? children : <Navigate to="/login" replace />
}

export function AppRouter() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
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
        <Route path="users" element={<UsersPage />} />
      </Route>
    </Routes>
  )
}
