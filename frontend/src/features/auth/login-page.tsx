import { useState, type FormEvent } from 'react'
import { ArrowRight, ShieldCheck, Workflow } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Button } from '../../components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from '../../components/ui/card'
import { Input } from '../../components/ui/input'
import { isPasswordChangeRequired } from '../api/lms-api'
import { useAuth } from './auth-context'

export function LoginPage() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const [username, setUsername] = useState('ops.admin')
  const [password, setPassword] = useState('ChangeMe123!')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')

    try {
      const session = await login({ username, password })
      navigate(session.mustChangePassword ? '/change-password' : '/dashboard')
    } catch (submitError) {
      const message = isPasswordChangeRequired(submitError)
        ? 'This account must set a new password before continuing.'
        : submitError instanceof Error
          ? submitError.message
          : 'Unable to sign in.'
      setError(message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="auth-layout">
      <section className="auth-panel">
        <Card className="form-card">
          <CardHeader>
            <div className="section-eyebrow">Internal Access</div>
            <CardTitle>Sovereign Ledger Login</CardTitle>
            <CardDescription>
              Use your internal credentials to enter the internal operations console.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleSubmit}>
              <div className="field-stack">
                <label htmlFor="username">Username</label>
                <Input
                  id="username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                />
              </div>
              <div className="field-stack">
                <label htmlFor="password">Password</label>
                <Input
                  id="password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              <div className="login-actions">
                <div className="helper-copy">
                  Managed or bootstrap credentials issue a JWT and unlock the internal Phase 2 routes.
                  Temporary passwords may require a one-time password update before access is granted.
                </div>
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Signing in...' : 'Enter console'}
                  <ArrowRight size={16} />
                </Button>
              </div>
            </form>
          </CardContent>
          <CardFooter>
            <div className="helper-copy">
              Phase 2 target: this form talks directly to the backend password login flow.
            </div>
          </CardFooter>
        </Card>
      </section>

      <section className="auth-hero">
        <div>
          <div className="auth-hero__eyebrow">Bhawana Capital</div>
          <h1 className="auth-hero__title">Loan operations, tenant control, and lender oversight.</h1>
          <p className="auth-hero__copy">
            A React + shadcn-aligned control plane for multi-tenant lending. Internal
            operators get all-LSP visibility, role-aware actions, and a path into
            product, loan, and borrower workflows.
          </p>
        </div>
        <div className="hero-grid">
          <div className="hero-grid__card">
            <ShieldCheck size={18} />
            <strong>JWT</strong>
            <span>Bootstrap auth for internal operators</span>
          </div>
          <div className="hero-grid__card">
            <Workflow size={18} />
            <strong>4</strong>
            <span>Ops lanes primed for the next phase</span>
          </div>
          <div className="hero-grid__card">
            <ArrowRight size={18} />
            <strong>All LSPs</strong>
            <span>Cross-tenant visibility preserved for internal roles</span>
          </div>
        </div>
      </section>
    </div>
  )
}
