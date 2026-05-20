import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { ShieldCheck, ArrowRight, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { auth } from "@/mocks/api";
import { useSession } from "@/features/auth/session-context";
import { defaultLandingFor } from "@/lib/role-gates";
import { SEED_USERS, type SeedUser } from "@/mocks/db/seed";
import { cn } from "@/lib/utils";
import { PageEyebrow } from "@/components/app/layout/PageEyebrow";

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
 * Mock-only sign-in surface. Renders a card per seed user; a click invokes
 * `auth.login()`, hydrates the session via `signIn`, and routes to the role's
 * default landing (`/home` or `/my-loans`). No password is required because
 * the mock router does not validate credentials (CLAUDE.md mock discipline).
 */
export function LoginPage() {
  const { session, isLoading, signIn } = useSession();
  const navigate = useNavigate();
  const [pending, setPending] = useState<string | null>(null);
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

  async function handleSelect(user: SeedUser): Promise<void> {
    setError(null);
    setPending(user.username);
    try {
      const next = await auth.login({ username: user.username, password: user.password });
      signIn(next);
      const target = next.user.mustChangePassword
        ? "/change-password"
        : defaultLandingFor(next.user.role);
      navigate(target, { replace: true });
    } catch (err) {
      const message = err instanceof Error ? err.message : "Sign-in failed";
      setError(message);
      setPending(null);
    }
  }

  return (
    <main className="bg-background flex min-h-screen flex-col items-center justify-center p-6">
      <div className="flex w-full max-w-3xl flex-col gap-8">
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
              Sign in as one of the seeded demo accounts. The mock router does not validate
              passwords.
            </p>
          </div>
        </header>

        {error ? (
          <div
            role="alert"
            className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border px-4 py-3 text-sm"
          >
            {error}
          </div>
        ) : null}

        <ul
          aria-label="Demo accounts"
          className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
        >
          {SEED_USERS.map((user) => {
            const copy = copyFor(user.role, user.mustChangePassword);
            const isPending = pending === user.username;
            return (
              <li key={user.id}>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => void handleSelect(user)}
                  disabled={pending !== null}
                  aria-label={`Sign in as ${user.username} — ${copy.title}`}
                  className={cn(
                    "h-auto w-full flex-col items-start justify-start gap-2 px-4 py-4 text-left whitespace-normal",
                  )}
                >
                  <span className="flex w-full items-start justify-between gap-2">
                    <span className="text-foreground text-sm font-semibold">{copy.title}</span>
                    {isPending ? (
                      <Loader2
                        aria-hidden="true"
                        className="text-foreground-muted h-3.5 w-3.5 animate-spin"
                      />
                    ) : (
                      <ArrowRight
                        aria-hidden="true"
                        className="text-foreground-muted h-3.5 w-3.5"
                      />
                    )}
                  </span>
                  <span className="text-foreground-muted text-xs leading-5">{copy.blurb}</span>
                  <span className="text-foreground-subtle font-mono text-[11px]">
                    {user.username} · {user.role}
                  </span>
                </Button>
              </li>
            );
          })}
        </ul>

        <p className="text-foreground-subtle text-center text-xs">
          UI-only build — every API call is in-process, every payload is mocked.
        </p>
      </div>
    </main>
  );
}

export default LoginPage;
export const Component = LoginPage;
