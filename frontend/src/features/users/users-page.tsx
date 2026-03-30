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
  listUsers,
  roleOptions,
  userStatusOptions,
  type RoleCode,
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
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('TempPass123!')
  const [role, setRole] = useState<RoleCode>('OPS_USER')
  const [status, setStatus] = useState<UserStatus>('ACTIVE')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    async function loadUsers() {
      setLoading(true)
      setError('')

      try {
        const response = await listUsers()
        if (!cancelled) {
          setUsers(response)
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

    setSubmitting(true)
    setError('')

    try {
      const created = await createUser({
        username,
        email,
        password,
        status,
        roles: [role],
      })

      setUsers((current) => [created, ...current.filter((item) => item.id !== created.id)])
      setUsername('')
      setEmail('')
      setPassword('TempPass123!')
      setRole('OPS_USER')
      setStatus('ACTIVE')
    } catch (createError) {
      const message = createError instanceof Error ? createError.message : 'Unable to create user.'
      setError(message)
    } finally {
      setSubmitting(false)
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
            <Badge variant="warning">{roleOptions.length} role codes</Badge>
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
                </div>
              ))}
              {!users.length ? <div className="empty-state">No users found.</div> : null}
            </div>
          ) : null}
        </CardContent>
      </Card>

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
              >
                {roleOptions.map((option) => (
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
              >
                {userStatusOptions.map((option) => (
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
