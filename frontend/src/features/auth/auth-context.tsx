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
  getSystemContext,
  loadStoredSession,
  loginWithPassword,
  saveStoredSession,
  type AuthSession,
  type AuthUser,
} from '../api/lms-api'

type LoginRequest = {
  username: string
  password: string
}

type AuthContextValue = {
  user: AuthUser | null
  login: (request: LoginRequest) => Promise<void>
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

async function createSession(username: string, password: string) {
  const token = await loginWithPassword(username, password)
  const context = await getSystemContext(token.accessToken)

  return {
    accessToken: token.accessToken,
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

  const value = useMemo<AuthContextValue>(
    () => ({
      user: buildUserFromSession(session),
      login: async ({ username, password }) => {
        if (!username.trim() || !password.trim()) {
          throw new Error('Username and password are required.')
        }

        const nextSession = await createSession(username, password)
        saveStoredSession(nextSession)
        startTransition(() => {
          setSession(nextSession)
        })
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
