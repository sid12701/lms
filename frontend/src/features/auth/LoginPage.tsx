import { useMemo, useReducer, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { Loader2, Eye, EyeOff, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useSession } from "@/features/auth/session-context";
import { login } from "@/features/auth/auth-service";
import {
  LOGIN_UI_ROLES,
  loginPresetForRole,
  type LoginRolePreset,
  type LoginUiRole,
} from "@/features/auth/login-role-presets";
import { defaultLandingFor } from "@/lib/role-gates";
import { cn } from "@/lib/utils";
import { PageEyebrow } from "@/components/app/layout/PageEyebrow";
import { ApiError } from "@/lib/api/http-client";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { LOGIN_PAGE_HEADING } from "@/lib/product-branding";

interface RoleCardCopy {
  title: string;
  blurb: string;
}

interface ConfiguredRoleCard {
  role: LoginUiRole;
  preset: LoginRolePreset;
}

interface LoginFormState {
  email: string;
  password: string;
  selectedRole: LoginUiRole | null;
  showPassword: boolean;
  submitting: boolean;
  error: string | null;
}

type LoginFormAction =
  | { type: "email-changed"; email: string }
  | { type: "password-changed"; password: string }
  | { type: "password-visibility-toggled" }
  | { type: "role-selected"; role: LoginUiRole; email: string }
  | { type: "submit-started" }
  | { type: "submit-failed"; error: string };

const INITIAL_LOGIN_FORM_STATE: LoginFormState = {
  email: "",
  password: "",
  selectedRole: null,
  showPassword: false,
  submitting: false,
  error: null,
};

function loginFormReducer(state: LoginFormState, action: LoginFormAction): LoginFormState {
  switch (action.type) {
    case "email-changed":
      return { ...state, email: action.email, selectedRole: null };
    case "password-changed":
      return { ...state, password: action.password };
    case "password-visibility-toggled":
      return { ...state, showPassword: !state.showPassword };
    case "role-selected":
      return { ...state, email: action.email, selectedRole: action.role, error: null };
    case "submit-started":
      return { ...state, submitting: true, error: null };
    case "submit-failed":
      return { ...state, submitting: false, error: action.error };
  }
}

function copyFor(role: LoginUiRole): RoleCardCopy {
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
  }
}

export function LoginPage() {
  const { session, isLoading, signIn } = useSession();
  const navigate = useNavigate();
  const [formState, dispatch] = useReducer(loginFormReducer, INITIAL_LOGIN_FORM_STATE);
  const { email, password, selectedRole, showPassword, submitting, error } = formState;
  const configuredRoleCards = useMemo<ConfiguredRoleCard[]>(
    () =>
      LOGIN_UI_ROLES.flatMap((role) => {
        const preset = loginPresetForRole(role);
        return preset ? [{ role, preset }] : [];
      }),
    [],
  );

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
    dispatch({ type: "submit-started" });
    try {
      const next = await login({ email, password });
      signIn(next);
      const target = next.user.mustChangePassword
        ? "/change-password"
        : defaultLandingFor(next.user.role);
      navigate(target, { replace: true });
    } catch (err) {
      const message =
        err instanceof ApiError && err.status === 401
          ? "Invalid email or password."
          : mapApiErrorMessage(err, "Sign-in failed.");
      dispatch({ type: "submit-failed", error: message });
    }
  }

  function handleRoleSelect(role: LoginUiRole, preset: LoginRolePreset): void {
    dispatch({ type: "role-selected", role, email: preset.email });
  }

  return (
    <main className="bg-background relative flex min-h-screen flex-col items-center justify-center p-6">
      <div
        aria-hidden="true"
        className="from-brand-100/40 dark:from-brand-950/30 pointer-events-none absolute inset-x-0 top-0 -z-10 h-[420px] bg-linear-to-b to-transparent"
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
          className="border-border bg-card mx-auto flex w-full max-w-md flex-col gap-5 rounded-2xl border p-7 shadow-lg shadow-black/3 sm:p-8"
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="email" className="text-sm font-medium">
              Email
            </Label>
            <Input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(event) => dispatch({ type: "email-changed", email: event.target.value })}
              disabled={submitting}
              required
              placeholder="you@company.com"
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
                onChange={(event) =>
                  dispatch({ type: "password-changed", password: event.target.value })
                }
                disabled={submitting}
                required
                placeholder="Enter your password"
                className="h-10 rounded-lg px-3 pr-10 text-sm"
              />
              <button
                type="button"
                onClick={() => dispatch({ type: "password-visibility-toggled" })}
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
              className="border-danger/30 bg-danger/5 text-danger flex items-start gap-2 rounded-lg border px-3 py-2.5 text-sm leading-5"
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

        {import.meta.env.DEV && configuredRoleCards.length > 0 ? (
          <section
            aria-labelledby="role-quick-fill-title"
            className="border-border bg-muted/30 flex flex-col gap-4 rounded-2xl border p-6"
          >
            <header className="flex flex-col gap-1">
              <h2
                id="role-quick-fill-title"
                className="text-foreground text-base font-semibold tracking-tight"
              >
                Sign in by role
              </h2>
              <p className="text-foreground-muted text-xs leading-5">
                Click a role to fill the email for a matching backend account. Configure local
                emails in <code className="font-mono">.env.local</code>; enter the password yourself
                so it never ships in the browser bundle.
              </p>
            </header>

            <ul
              aria-label="System roles"
              className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
            >
              {configuredRoleCards.map(({ role, preset }) => {
                const copy = copyFor(role);
                const isActive = selectedRole === role;
                return (
                  <li key={role}>
                    <button
                      type="button"
                      onClick={() => handleRoleSelect(role, preset)}
                      disabled={submitting}
                      aria-label={`Fill email for ${copy.title}`}
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
                      <span className="text-foreground-subtle font-mono text-[11px]">{role}</span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}
      </div>
    </main>
  );
}

export default LoginPage;
export const Component = LoginPage;
