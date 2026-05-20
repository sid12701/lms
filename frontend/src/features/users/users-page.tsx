import { useEffect, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { BlueLoader } from '@/components/app/blue-loader'
import {
  AdminBadge,
  AdminButton,
  AdminContent,
  AdminDescription,
  AdminEmptyState,
  AdminEyebrow,
  AdminField,
  AdminFieldLabel,
  AdminHeader,
  AdminInput,
  AdminSelect,
  AdminSurface,
  AdminTitle,
} from '@/components/app/admin-page-ui'
import { queryKeys } from '../api/query-keys'
import { ApiError } from '../api/http-client'
import type {
  AdminMetadata,
  LspOptionRecord,
  ResetPasswordResponse,
  RoleCode,
  UserRecord,
  UserStatus,
} from '../api/lms-api'
import { createUser, getAdminMetadata, listLspOptions, listUsers, resetUserPassword } from '../api/admin-api'

function statusVariant(status: UserStatus): 'success' | 'warning' {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function isAccessError(error: unknown) {
  return error instanceof ApiError && (error.status === 401 || error.status === 403)
}

export function UsersPage() {
  const queryClient = useQueryClient()
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('TempPass123!')
  const [role, setRole] = useState<RoleCode | ''>('')
  const [lspId, setLspId] = useState('')
  const [status, setStatus] = useState<UserStatus | ''>('')
  const [submitting, setSubmitting] = useState(false)
  const [resettingUserId, setResettingUserId] = useState('')
  const [resetResult, setResetResult] = useState<ResetPasswordResponse | null>(null)
  const [localError, setLocalError] = useState('')

  const metadataQuery = useQuery({
    queryKey: queryKeys.adminMetadata,
    queryFn: getAdminMetadata,
  })
  const lspQuery = useQuery({
    queryKey: queryKeys.lspOptions,
    queryFn: listLspOptions,
  })
  const usersQuery = useQuery({
    queryKey: queryKeys.users,
    queryFn: listUsers,
  })

  const metadata: AdminMetadata | null = metadataQuery.data ?? null
  const lsps: LspOptionRecord[] = lspQuery.data ?? []
  const users: UserRecord[] = usersQuery.data ?? []
  const loading = metadataQuery.isLoading || lspQuery.isLoading || usersQuery.isLoading
  const permissionDenied =
    isAccessError(metadataQuery.error) || isAccessError(lspQuery.error) || isAccessError(usersQuery.error)
  const queryError = permissionDenied ? null : metadataQuery.error ?? lspQuery.error ?? usersQuery.error
  const error = localError || (queryError instanceof Error ? queryError.message : '')

  useEffect(() => {
    if (!metadata) {
      return
    }
    setRole((current) => current || (metadata.roleCodes[0] as RoleCode | ''))
    setStatus((current) => current || (metadata.userStatuses[0] as UserStatus | ''))
  }, [metadata])

  const requiresLsp = role === 'LSP_UI_READ' || role === 'LSP_UI_WRITE'
  const formDisabled = permissionDenied || !metadata

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (formDisabled || !username.trim() || !email.trim() || !password.trim()) {
      return
    }

    const nextRole = role || (metadata?.roleCodes[0] as RoleCode | undefined) || 'OPS_USER'
    const nextStatus = status || (metadata?.userStatuses[0] as UserStatus | undefined) || 'ACTIVE'
    const nextLspId = requiresLsp ? lspId || lsps[0]?.id || '' : ''

    if (requiresLsp && !nextLspId) {
      setLocalError('An LSP must be selected for tenant UI users.')
      return
    }

    setSubmitting(true)
    setLocalError('')

    try {
      const created = await createUser({
        username,
        email,
        password,
        status: nextStatus,
        lspId: nextLspId || null,
        roles: [nextRole],
      })

      queryClient.setQueryData<UserRecord[]>(queryKeys.users, (current = []) => [
        created,
        ...current.filter((item) => item.id !== created.id),
      ])
      setUsername('')
      setEmail('')
      setPassword('TempPass123!')
      setRole(nextRole)
      setLspId(nextLspId)
      setStatus(nextStatus)
    } catch (createError) {
      const message = createError instanceof Error ? createError.message : 'Unable to create user.'
      setLocalError(message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleResetPassword(userId: string) {
    setResettingUserId(userId)
    setLocalError('')
    setResetResult(null)

    try {
      const result = await resetUserPassword(userId)
      setResetResult(result)
    } catch (resetError) {
      const message = resetError instanceof Error ? resetError.message : 'Unable to reset password.'
      setLocalError(message)
    } finally {
      setResettingUserId('')
    }
  }

  return (
    <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(360px,0.48fr)]">
      <AdminSurface>
        <AdminHeader>
          <AdminEyebrow>User administration</AdminEyebrow>
          <AdminTitle>Internal and tenant-scoped users</AdminTitle>
          <AdminDescription>
            Manage internal operators, role assignment, and tenant scope from one registry.
          </AdminDescription>
        </AdminHeader>
        <AdminContent>
          <div className="flex flex-wrap items-center gap-2">
            <AdminBadge>{users.length} users</AdminBadge>
            <AdminBadge variant="warning">{metadata?.roleCodes.length ?? 0} role codes</AdminBadge>
          </div>
          {loading ? (
            <BlueLoader
              title="Loading user registry"
              description="Fetching internal users, tenant scopes, and role metadata."
              compact
            />
          ) : null}
          {permissionDenied ? (
            <AdminEmptyState>
              System-admin access is required to manage users. Sign in with an account that includes user
              administration permissions.
            </AdminEmptyState>
          ) : null}
          {error ? <AdminEmptyState>{error}</AdminEmptyState> : null}
          {!loading && !permissionDenied && !error ? (
            <div className="grid gap-3">
              {users.map((user) => (
                <article
                  className="grid gap-4 rounded-lg bg-[#f8f9fa] p-4 shadow-[0_8px_24px_rgba(0,6,102,0.045)] transition duration-200 hover:-translate-y-0.5 hover:bg-white hover:shadow-[0_16px_34px_rgba(0,6,102,0.08)] lg:grid-cols-[minmax(180px,1fr)_auto_auto_minmax(120px,0.6fr)_auto] lg:items-center"
                  key={user.id}
                >
                  <div className="min-w-0">
                    <strong className="block truncate text-base font-extrabold text-[#0f1729]">
                      {user.username}
                    </strong>
                    <p className="mt-1 truncate text-sm font-semibold text-[#5e6680]">{user.email}</p>
                  </div>
                  <AdminBadge variant={statusVariant(user.status)}>{user.status}</AdminBadge>
                  <AdminBadge>{user.roles[0] ?? 'UNASSIGNED'}</AdminBadge>
                  <span className="min-w-0 truncate text-sm font-semibold text-[#5e6680]">
                    {user.lspName || 'All tenants'}
                  </span>
                  <AdminButton
                    disabled={resettingUserId === user.id}
                    size="sm"
                    variant="secondary"
                    onClick={() => handleResetPassword(user.id)}
                  >
                    {resettingUserId === user.id ? 'Resetting...' : 'Reset password'}
                  </AdminButton>
                </article>
              ))}
              {!users.length ? <AdminEmptyState>No users found.</AdminEmptyState> : null}
            </div>
          ) : null}
        </AdminContent>
      </AdminSurface>

      <div className="grid gap-6">
        {resetResult ? (
          <AdminSurface>
            <AdminHeader>
              <AdminEyebrow>Temporary password issued</AdminEyebrow>
              <AdminTitle>{resetResult.username}</AdminTitle>
              <AdminDescription>This password is shown only once after reset.</AdminDescription>
            </AdminHeader>
            <AdminContent>
              <div className="grid gap-3 rounded-lg bg-[#eef1f8]/80 p-4">
                <div className="grid gap-1">
                  <span className="text-xs font-bold uppercase text-[#8a92a8]">User</span>
                  <strong className="break-all text-sm font-extrabold text-[#0f1729]">{resetResult.username}</strong>
                </div>
                <div className="grid gap-1">
                  <span className="text-xs font-bold uppercase text-[#8a92a8]">
                    Temporary password
                  </span>
                  <strong className="break-all rounded-md bg-white px-3 py-2 text-sm font-extrabold text-[#000666] shadow-[inset_0_0_0_1px_rgba(0,6,102,0.05)]">
                    {resetResult.temporaryPassword}
                  </strong>
                </div>
                <AdminButton variant="ghost" onClick={() => setResetResult(null)}>
                  Acknowledge and hide password
                </AdminButton>
              </div>
            </AdminContent>
          </AdminSurface>
        ) : null}

        <AdminSurface>
          <AdminHeader>
            <AdminEyebrow>Create user</AdminEyebrow>
            <AdminTitle>Add operator</AdminTitle>
            <AdminDescription>Issue a temporary password and assign the role needed for console access.</AdminDescription>
          </AdminHeader>
          <AdminContent>
            {permissionDenied ? (
              <AdminEmptyState>
                User creation is disabled for this session because the active token lacks user administration
                permissions.
              </AdminEmptyState>
            ) : null}
            <form className="grid gap-4" onSubmit={handleCreate}>
              <AdminField>
                <AdminFieldLabel htmlFor="username">Username</AdminFieldLabel>
                <AdminInput
                  disabled={formDisabled}
                  id="username"
                  placeholder="ananya.ops"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                />
              </AdminField>
              <AdminField>
                <AdminFieldLabel htmlFor="email">Email</AdminFieldLabel>
                <AdminInput
                  disabled={formDisabled}
                  id="email"
                  placeholder="ananya.ops@bhawana.local"
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                />
              </AdminField>
              <AdminField>
                <AdminFieldLabel htmlFor="password">Temporary password</AdminFieldLabel>
                <AdminInput
                  disabled={formDisabled}
                  id="password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </AdminField>
              <AdminField>
                <AdminFieldLabel htmlFor="role">Role code</AdminFieldLabel>
                <AdminSelect
                  disabled={formDisabled || !metadata?.roleCodes.length}
                  id="role"
                  value={role}
                  onChange={(event) => setRole(event.target.value as RoleCode)}
                >
                  <option value="">Select a role</option>
                  {(metadata?.roleCodes ?? []).map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </AdminSelect>
              </AdminField>
              <AdminField>
                <AdminFieldLabel htmlFor="status">Status</AdminFieldLabel>
                <AdminSelect
                  disabled={formDisabled || !metadata?.userStatuses.length}
                  id="status"
                  value={status}
                  onChange={(event) => setStatus(event.target.value as UserStatus)}
                >
                  <option value="">Select a status</option>
                  {(metadata?.userStatuses ?? []).map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </AdminSelect>
              </AdminField>
              <AdminField>
                <AdminFieldLabel htmlFor="lspId">Tenant scope</AdminFieldLabel>
                <AdminSelect
                  disabled={formDisabled || !lsps.length || !requiresLsp}
                  id="lspId"
                  value={lspId}
                  onChange={(event) => setLspId(event.target.value)}
                >
                  <option value="">{requiresLsp ? 'Select an LSP' : 'Not required for this role'}</option>
                  {lsps.map((option) => (
                    <option key={option.id} value={option.id}>
                      {option.name}
                    </option>
                  ))}
                </AdminSelect>
              </AdminField>
              {error ? <AdminEmptyState>{error}</AdminEmptyState> : null}
              <AdminButton disabled={formDisabled || submitting} type="submit">
                {submitting ? 'Creating...' : 'Create user'}
              </AdminButton>
            </form>
          </AdminContent>
        </AdminSurface>
      </div>
    </div>
  )
}
