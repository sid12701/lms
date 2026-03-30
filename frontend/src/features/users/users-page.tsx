import { useEffect, useState, type FormEvent } from 'react'
import { Badge } from '../../components/ui/badge'
import { Button } from '../../components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import {
  createUser,
  getAdminMetadata,
  listUsers,
  resetUserPassword,
  type AdminMetadata,
  type RoleCode,
  type ResetPasswordResponse,
  type UserRecord,
  type UserStatus,
} from '../api/lms-api'

function statusVariant(status: UserStatus): 'success' | 'warning' {
  if (status === 'ACTIVE') {
    return 'success'
  }

  return 'warning'
}

export function UsersPage() {
  const [users, setUsers] = useState<UserRecord[]>([])
  const [metadata, setMetadata] = useState<AdminMetadata | null>(null)
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('TempPass123!')
  const [role, setRole] = useState<RoleCode | ''>('')
  const [status, setStatus] = useState<UserStatus | ''>('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [resettingUserId, setResettingUserId] = useState('')
  const [resetResult, setResetResult] = useState<ResetPasswordResponse | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function loadUsers() {
      setLoading(true)
      setError('')

      try {
        const [metadataResponse, response] = await Promise.all([getAdminMetadata(), listUsers()])
        if (!cancelled) {
          setMetadata(metadataResponse)
          setUsers(response)
          setRole((current) => current || (metadataResponse.roleCodes[0] as RoleCode | ''))
          setStatus((current) => current || (metadataResponse.userStatuses[0] as UserStatus | ''))
        }
      } catch (loadError) {
        const message = loadError instanceof Error ? loadError.message : 'Unable to load users.'
        if (!cancelled) {
          setError(message)
        }
      } finally {
        if (!cancelled) {
          setLoading(false)
        }
      }
    }

    void loadUsers()

    return () => {
      cancelled = true
    }
  }, [])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!username.trim() || !email.trim() || !password.trim()) {
      return
    }

    const nextRole = role || (metadata?.roleCodes[0] as RoleCode | undefined) || 'OPS_USER'
    const nextStatus = status || (metadata?.userStatuses[0] as UserStatus | undefined) || 'ACTIVE'

    setSubmitting(true)
    setError('')

    try {
      const created = await createUser({
        username,
        email,
        password,
        status: nextStatus,
        roles: [nextRole],
      })

      setUsers((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setUsername('')
      setEmail('')
      setPassword('TempPass123!')
      setRole(nextRole)
      setStatus(nextStatus)
    } catch (createError) {
      const message = createError instanceof Error ? createError.message : 'Unable to create user.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleResetPassword(userId: string) {
    setResettingUserId(userId)
    setError('')
    setResetResult(null)

    try {
      const result = await resetUserPassword(userId)
      setResetResult(result)
    } catch (resetError) {
      const message = resetError instanceof Error ? resetError.message : 'Unable to reset password.'
      setError(message)
    } finally {
      setResettingUserId('')
    }
  }

  return (
    <div className="users-layout">
      <Card className="list-card">
        <CardHeader>
          <div className="section-eyebrow">User administration</div>
          <CardTitle>Internal and tenant-scoped users</CardTitle>
          <CardDescription>
            Phase 2 target: manage internal users, role assignment, and tenant scope from a single console.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="inline-actions" style={{ marginBottom: '1rem' }}>
            <Badge>{users.length} users</Badge>
            <Badge variant="warning">{metadata?.roleCodes.length ?? 0} role codes</Badge>
          </div>
          {loading ? <div className="empty-state">Loading user registry...</div> : null}
          {error ? <div className="empty-state">{error}</div> : null}
          {!loading && !error ? (
            <div className="table-grid">
              {users.map((user) => (
                <div className="table-row" key={user.id}>
                  <div>
                    <strong>{user.username}</strong>
                    <p className="helper-copy">{user.email}</p>
                  </div>
                  <Badge variant={statusVariant(user.status)}>{user.status}</Badge>
                  <Badge>{user.roles[0] ?? 'UNASSIGNED'}</Badge>
                  <span>{user.lspName}</span>
                  <Button
                    type="button"
                    variant="ghost"
                    disabled={resettingUserId === user.id}
                    onClick={() => handleResetPassword(user.id)}
                  >
                    {resettingUserId === user.id ? 'Resetting...' : 'Reset password'}
                  </Button>
                </div>
              ))}
              {!users.length ? <div className="empty-state">No users found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

      {resetResult ? (
        <Card className="content-card">
          <CardHeader>
            <div className="section-eyebrow">Temporary password issued</div>
            <CardTitle>{resetResult.username}</CardTitle>
            <CardDescription>
              Copy this password now. It is shown only once after reset.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="secret-panel">
              <div className="secret-panel__row">
                <span>User</span>
                <strong>{resetResult.username}</strong>
              </div>
              <div className="secret-panel__row">
                <span>Temporary password</span>
                <strong>{resetResult.temporaryPassword}</strong>
              </div>
              <Button type="button" variant="ghost" onClick={() => setResetResult(null)}>
                Acknowledge and hide password
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <div className="section-eyebrow">Create user</div>
          <CardTitle>Add operator</CardTitle>
          <CardDescription>
            This shell form is wired to the backend user creation API.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form className="form-grid" onSubmit={handleCreate}>
            <div className="field-stack">
              <label htmlFor="username">Username</label>
              <Input
                id="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="ananya.ops"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="email">Email</label>
              <Input
                id="email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="ananya.ops@bhawana.local"
              />
            </div>
            <div className="field-stack">
              <label htmlFor="password">Temporary password</label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </div>
            <div className="field-stack">
              <label htmlFor="role">Role code</label>
              <select
                id="role"
                className="ui-input"
                value={role}
                onChange={(event) => setRole(event.target.value as RoleCode)}
                disabled={!metadata?.roleCodes.length}
              >
                <option value="">Select a role</option>
                {(metadata?.roleCodes ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>
            <div className="field-stack">
              <label htmlFor="status">Status</label>
              <select
                id="status"
                className="ui-input"
                value={status}
                onChange={(event) => setStatus(event.target.value as UserStatus)}
                disabled={!metadata?.userStatuses.length}
              >
                <option value="">Select a status</option>
                {(metadata?.userStatuses ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            </div>
            {error ? <div className="empty-state">{error}</div> : null}
            <Button disabled={submitting} type="submit">
              {submitting ? 'Creating...' : 'Create user'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
