import { useState, type FormEvent } from 'react'
import { ArrowRight, KeyRound, ShieldCheck } from 'lucide-react'
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
import { useAuth } from './auth-context'

export function ChangePasswordPage() {
  const navigate = useNavigate()
  const { user, completePasswordChange } = useAuth()
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError('')

    if (!newPassword.trim() || !confirmPassword.trim()) {
      setError('Enter and confirm a new password.')
      return
    }

    if (newPassword !== confirmPassword) {
      setError('Passwords do not match.')
      return
    }

    setSubmitting(true)

    try {
      await completePasswordChange(newPassword)
      navigate('/dashboard', { replace: true })
    } catch (submitError) {
      const message =
        submitError instanceof Error ? submitError.message : 'Unable to change password.'
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
            <div className="section-eyebrow">Mandatory update</div>
            <CardTitle>Set a permanent password</CardTitle>
            <CardDescription>
              {user?.username
                ? `This account (${user.username}) was issued a temporary password. Set a new one to finish signing in.`
                : 'This account was issued a temporary password. Set a new one to finish signing in.'}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form className="form-grid" onSubmit={handleSubmit}>
              <div className="field-stack">
                <label htmlFor="new-password">New password</label>
                <Input
                  id="new-password"
                  type="password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  autoComplete="new-password"
                />
              </div>
              <div className="field-stack">
                <label htmlFor="confirm-password">Confirm password</label>
                <Input
                  id="confirm-password"
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                />
              </div>
              {error ? <div className="empty-state">{error}</div> : null}
              <div className="login-actions">
                <div className="helper-copy">
                  Use a password that is not the temporary reset value. Keep it private and at least the
                  strength required by the backend policy.
                </div>
                <Button disabled={submitting} type="submit">
                  {submitting ? 'Updating...' : 'Update password'}
                  <ArrowRight size={16} />
                </Button>
              </div>
            </form>
          </CardContent>
          <CardFooter>
            <div className="helper-copy">
              The session will be refreshed automatically after the password update completes.
            </div>
          </CardFooter>
        </Card>
      </section>

      <section className="auth-hero">
        <div>
          <div className="auth-hero__eyebrow">Secure handoff</div>
          <h1 className="auth-hero__title">Temporary access, then permanent control.</h1>
          <p className="auth-hero__copy">
            Managed users receive a reset password from internal administration. The first login
            must complete this handoff before the console opens.
          </p>
        </div>
        <div className="hero-grid">
          <div className="hero-grid__card">
            <ShieldCheck size={18} />
            <strong>Locked</strong>
            <span>Normal routes stay blocked until the reset is completed</span>
          </div>
          <div className="hero-grid__card">
            <KeyRound size={18} />
            <strong>New password</strong>
            <span>One permanent credential replaces the temporary reset value</span>
          </div>
          <div className="hero-grid__card">
            <ArrowRight size={18} />
            <strong>Resume</strong>
            <span>After update, the session drops back into the standard shell</span>
          </div>
        </div>
      </section>
    </div>
  )
}
