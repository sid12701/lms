import {
  createContext,
  startTransition,
  useContext,
  useMemo,
  useState,
  type PropsWithChildren,
} from 'react'
import {
  clearStoredSession,
  completePasswordChange,
  getSystemContext,
  loadStoredSession,
  loginWithPassword,
  saveStoredSession,
  refreshAccessToken,
  type AuthSession,
  type AuthUser,
} from '../api/lms-api'
import { useEffect, useRef } from 'react'

type LoginRequest = {
  username: string
  password: string
}

type AuthContextValue = {
  user: AuthUser | null
  mustChangePassword: boolean
  login: (request: LoginRequest) => Promise<AuthSession>
  completePasswordChange: (newPassword: string) => Promise<AuthSession>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

function scopeForRoles(roles: string[]) {
  if (roles.includes('SYSTEM_ADMIN')) {
    return 'All LSPs'
  }

  if (roles.includes('OPS_USER')) {
    return 'Operations'
  }

  return 'Tenant scope'
}

function buildUserFromSession(session: AuthSession | null): AuthUser | null {
  return session?.user ?? null
}

async function buildSessionFromToken(
  accessToken: string,
  options: { mustChangePassword?: boolean } = {},
) {
  const context = await getSystemContext(accessToken)
  return {
    accessToken,
    mustChangePassword: options.mustChangePassword ?? false,
    user: {
      username: context.username,
      primaryRole: context.roles[0] ?? 'UNKNOWN',
      scope: scopeForRoles(context.roles),
      application: context.application,
      activeProfiles: context.activeProfiles,
      correlationId: context.correlationId,
    },
  } satisfies AuthSession
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<AuthSession | null>(() => loadStoredSession())
  const bootstrapped = useRef(false)

  useEffect(() => {
    if (bootstrapped.current) {
      return
    }
    bootstrapped.current = true

    const currentSession = session
    if (!currentSession || currentSession.mustChangePassword) {
      return
    }

    let cancelled = false

    async function refreshStoredSession(accessToken: string) {
      try {
        const token = await refreshAccessToken(accessToken)
        const nextSession = await buildSessionFromToken(token.accessToken)
        if (!cancelled) {
          saveStoredSession(nextSession)
          startTransition(() => {
            setSession(nextSession)
          })
        }
      } catch {
        if (!cancelled) {
          clearStoredSession()
          startTransition(() => {
            setSession(null)
          })
        }
      }
    }

    void refreshStoredSession(currentSession.accessToken)

    return () => {
      cancelled = true
    }
  }, [session])

  const value = useMemo<AuthContextValue>(
    () => ({
      user: buildUserFromSession(session),
      mustChangePassword: session?.mustChangePassword ?? false,
      login: async ({ username, password }) => {
        if (!username.trim() || !password.trim()) {
          throw new Error('Username and password are required.')
        }

        const token = await loginWithPassword(username, password)
        const nextSession = await buildSessionFromToken(token.accessToken, {
          mustChangePassword: token.passwordChangeRequired ?? false,
        })
        saveStoredSession(nextSession)
        startTransition(() => {
          setSession(nextSession)
        })
        return nextSession
      },
      completePasswordChange: async (newPassword: string) => {
        if (!newPassword.trim()) {
          throw new Error('A new password is required.')
        }

        const token = await completePasswordChange({
          newPassword,
        })
        const nextSession = await buildSessionFromToken(token.accessToken, {
          mustChangePassword: token.passwordChangeRequired ?? false,
        })
        saveStoredSession(nextSession)
        startTransition(() => {
          setSession(nextSession)
        })
        return nextSession
      },
      logout: () => {
        clearStoredSession()
        startTransition(() => {
          setSession(null)
        })
      },
    }),
    [session],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
