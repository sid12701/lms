import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { ShieldCheck, Loader2 } from "lucide-react";
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

/**
 * Live sign-in surface plus a dev-mode role preview.
 *
 * The top half is the real form that POSTs `/api/v1/auth/login` against the
 * Spring backend. The bottom half lists the seeded backend accounts so a
 * developer can pick one and prefill the form — the password is not
 * auto-filled (the backend validates it), but the typed username matches a
 * known bootstrap user.
 */
export function LoginPage() {
  const { session, isLoading, signIn } = useSession();
  const navigate = useNavigate();
  const [username, setUsername] = useState<string>("");
  const [password, setPassword] = useState<string>("");
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
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
    setError(null);
  }

  return (
    <main className="bg-background flex min-h-screen flex-col items-center justify-center p-6">
      <div className="flex w-full max-w-3xl flex-col gap-10">
        <header className="flex flex-col items-center gap-3 text-center">
          <span
            aria-hidden="true"
            className="bg-brand-700 text-primary-foreground inline-flex h-10 w-10 items-center justify-center rounded-md"
          >
            <ShieldCheck className="h-5 w-5" />
          </span>
          <div>
            <PageEyebrow className="mb-1">Sign in</PageEyebrow>
            <h1 className="text-foreground text-2xl leading-8 font-semibold tracking-tight">
              Bhawana Capital — Loan Management
            </h1>
            <p className="text-foreground-muted mt-1 text-sm">
              Sign in with your backend credentials.
            </p>
          </div>
        </header>

        <form
          onSubmit={handleSubmit}
          aria-label="Sign in"
          className="border-border bg-card mx-auto flex w-full max-w-md flex-col gap-4 rounded-md border p-6 shadow-sm"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              name="username"
              autoComplete="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              disabled={submitting}
              required
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
              required
            />
          </div>

          {error ? (
            <div
              role="alert"
              className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-3 py-2 text-sm"
            >
              {error}
            </div>
          ) : null}

          <Button type="submit" disabled={submitting} className="w-full">
            {submitting ? (
              <>
                <Loader2 aria-hidden="true" className="mr-2 h-3.5 w-3.5 animate-spin" />
                Signing in…
              </>
            ) : (
              "Authenticate session"
            )}
          </Button>
        </form>

        <section
          aria-labelledby="dev-role-preview-title"
          className="border-border bg-muted/30 flex flex-col gap-4 rounded-md border p-5"
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
              These are the bootstrap accounts the backend seeds in dev mode. Click one to
              prefill its username — then enter the matching password to sign in. The role
              column shows where each account lands after authentication.
            </p>
          </header>

          <ul
            aria-label="Seeded backend accounts"
            className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
          >
            {SEED_USERS.map((user) => {
              const copy = copyFor(user.role, user.mustChangePassword);
              return (
                <li key={user.id}>
                  <button
                    type="button"
                    onClick={() => handlePrefill(user)}
                    disabled={submitting}
                    aria-label={`Prefill ${user.username} — ${copy.title}`}
                    className={cn(
                      "border-border bg-background hover:border-brand-500 hover:bg-brand-50/30 dark:hover:bg-brand-950/20 flex h-full w-full flex-col items-start gap-1 rounded-md border px-4 py-3 text-left text-sm transition",
                      "disabled:cursor-not-allowed disabled:opacity-50",
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
