import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { Loader2, Eye, EyeOff, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSession } from "@/features/auth/session-context";
import { login } from "@/features/auth/auth-service";
import { defaultLandingFor } from "@/lib/role-gates";
import { SEED_USERS, type SeedUser } from "@/mocks/db/seed";
import { cn } from "@/lib/utils";
import { PageEyebrow } from "@/components/app/layout/PageEyebrow";
import { ApiError } from "@/lib/api/http-client";
import { LOGIN_PAGE_HEADING } from "@/lib/product-branding";

interface RoleCardCopy {
  title: string;
  blurb: string;
}

function copyFor(role: SeedUser["role"], mustChange: boolean): RoleCardCopy {
  if (mustChange) {
    return {
      title: "Forced password change",
      blurb: "Lands on the change-password screen on first sign-in.",
    };
  }
  switch (role) {
    case "SYSTEM_ADMIN":
      return { title: "System administrator", blurb: "Full access across LSPs and admin." };
    case "OPS_USER":
      return { title: "Operations user", blurb: "Day-to-day ops across applications." };
    case "PRODUCT_ADMIN":
      return { title: "Product administrator", blurb: "Manages the product catalog." };
    case "LSP_UI_READ":
      return { title: "LSP user (read-only)", blurb: "Read-only access to a single LSP scope." };
    case "LSP_UI_WRITE":
      return { title: "LSP user (read/write)", blurb: "Originate and act within one LSP scope." };
    case "LSP_API_CLIENT":
      return { title: "LSP API client", blurb: "Server-to-server credential — UI hidden." };
  }
}

export function LoginPage() {
  const { session, isLoading, signIn } = useSession();
  const navigate = useNavigate();
  const [username, setUsername] = useState<string>("");
  const [password, setPassword] = useState<string>("");
  const [showPassword, setShowPassword] = useState<boolean>(false);
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  if (isLoading) {
    return (
      <div className="bg-background flex min-h-screen items-center justify-center">
        <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
        <span className="sr-only">Loading session</span>
      </div>
    );
  }

  if (session) {
    const target = session.user.mustChangePassword
      ? "/change-password"
      : defaultLandingFor(session.user.role);
    return <Navigate to={target} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const next = await login({ username, password });
      signIn(next);
      const target = next.user.mustChangePassword
        ? "/change-password"
        : defaultLandingFor(next.user.role);
      navigate(target, { replace: true });
    } catch (err) {
      const message =
        err instanceof ApiError
          ? err.status === 401
            ? "Invalid username or password."
            : (err.message ?? "Sign-in failed.")
          : err instanceof Error
            ? err.message
            : "Sign-in failed.";
      setError(message);
      setSubmitting(false);
    }
  }

  function handlePrefill(user: SeedUser): void {
    setUsername(user.username);
    setPassword("");
    setShowPassword(false);
    setError(null);
  }

  return (
    <main className="bg-background relative flex min-h-screen flex-col items-center justify-center p-6">
      <div
        aria-hidden="true"
        className="from-brand-100/40 dark:from-brand-950/30 pointer-events-none absolute inset-x-0 top-0 -z-10 h-[420px] bg-gradient-to-b to-transparent"
      />

      <div className="flex w-full max-w-3xl flex-col gap-10">
        <header className="flex flex-col items-center gap-4 text-center">
          <div>
            <PageEyebrow className="mb-1.5">Sign in</PageEyebrow>
            <h1 className="text-foreground text-2xl leading-8 font-semibold tracking-tight sm:text-[26px]">
              {LOGIN_PAGE_HEADING}
            </h1>
            <p className="text-foreground-muted mx-auto mt-2 max-w-sm text-sm leading-6">
              Sign in with your backend credentials to access the platform.
            </p>
          </div>
        </header>

        <form
          onSubmit={handleSubmit}
          aria-label="Sign in"
          aria-busy={submitting}
          className="border-border bg-card mx-auto flex w-full max-w-md flex-col gap-5 rounded-2xl border p-7 shadow-lg shadow-black/[0.03] sm:p-8"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="username" className="text-sm font-medium">
              Username
            </Label>
            <Input
              id="username"
              name="username"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              disabled={submitting}
              required
              placeholder="e.g. ops.admin"
              className="h-10 rounded-lg px-3 text-sm"
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="password" className="text-sm font-medium">
              Password
            </Label>
            <div className="relative">
              <Input
                id="password"
                name="password"
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                disabled={submitting}
                required
                placeholder="Enter your password"
                className="h-10 rounded-lg px-3 pr-10 text-sm"
              />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                disabled={submitting || password.length === 0}
                aria-label={showPassword ? "Hide password" : "Show password"}
                aria-pressed={showPassword}
                className={cn(
                  "text-foreground-muted hover:text-foreground focus-visible:ring-ring/40 absolute inset-y-0 right-0 inline-flex h-full w-10 items-center justify-center rounded-r-lg transition-colors outline-none focus-visible:ring-2",
                  "disabled:pointer-events-none disabled:opacity-40",
                )}
              >
                {showPassword ? (
                  <EyeOff aria-hidden="true" className="h-4 w-4" />
                ) : (
                  <Eye aria-hidden="true" className="h-4 w-4" />
                )}
              </button>
            </div>
          </div>

          {error ? (
            <div
              role="alert"
              className="border-destructive/30 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-3 py-2.5 text-sm leading-5"
            >
              <AlertCircle aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}

          <Button
            type="submit"
            disabled={submitting}
            aria-live="polite"
            className="mt-1 h-10 w-full rounded-lg text-sm font-semibold tracking-tight shadow-sm transition-all hover:shadow-md active:translate-y-0"
          >
            {submitting ? (
              <>
                <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />
                <span>Signing in…</span>
              </>
            ) : (
              <span>Sign in</span>
            )}
          </Button>
        </form>

        <section
          aria-labelledby="dev-role-preview-title"
          className="border-border bg-muted/30 flex flex-col gap-4 rounded-2xl border p-6"
        >
          <header className="flex flex-col gap-1">
            <PageEyebrow>Dev-mode preview</PageEyebrow>
            <h2
              id="dev-role-preview-title"
              className="text-foreground text-base font-semibold tracking-tight"
            >
              Available system roles
            </h2>
            <p className="text-foreground-muted text-xs leading-5">
              These are the bootstrap accounts the backend seeds in dev mode. Click one to prefill
              its username — then enter the matching password to sign in. The role column shows
              where each account lands after authentication.
            </p>
          </header>

          <ul
            aria-label="Seeded backend accounts"
            className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
          >
            {SEED_USERS.map((user) => {
              const copy = copyFor(user.role, user.mustChangePassword);
              const isActive = username === user.username;
              return (
                <li key={user.id}>
                  <button
                    type="button"
                    onClick={() => handlePrefill(user)}
                    disabled={submitting}
                    aria-label={`Prefill ${user.username} — ${copy.title}`}
                    aria-pressed={isActive}
                    className={cn(
                      "group/card border-border bg-background flex h-full w-full flex-col items-start gap-1 rounded-xl border px-4 py-3 text-left text-sm transition-all duration-150",
                      "hover:border-brand-500/60 hover:bg-brand-50/40 dark:hover:bg-brand-950/20 hover:-translate-y-px hover:shadow-sm",
                      "focus-visible:border-brand-500 focus-visible:ring-brand-500/30 outline-none focus-visible:ring-2",
                      "disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:translate-y-0 disabled:hover:shadow-none",
                      isActive &&
                        "border-brand-500 bg-brand-50/60 dark:bg-brand-950/30 ring-brand-500/20 ring-2",
                    )}
                  >
                    <span className="text-foreground font-semibold">{copy.title}</span>
                    <span className="text-foreground-muted text-xs leading-5">{copy.blurb}</span>
                    <span className="text-foreground-subtle font-mono text-[11px]">
                      {user.username} · {user.role}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        <p className="text-foreground-subtle text-center text-xs">
          The frontend calls the live Spring backend at{" "}
          <code className="font-mono">VITE_API_BASE_URL</code>. Override in{" "}
          <code className="font-mono">.env.local</code> if needed.
        </p>
      </div>
    </main>
  );
}

export default LoginPage;
export const Component = LoginPage;
