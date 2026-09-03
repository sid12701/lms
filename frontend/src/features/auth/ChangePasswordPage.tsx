import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Eye, EyeOff, KeyRound, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { FormShell } from "@/components/app/forms/FormShell";
import { completePasswordChange } from "@/features/auth/auth-service";
import { useSession } from "@/features/auth/session-context";
import { defaultLandingFor } from "@/lib/role-gates";
import { mapApiErrorMessage } from "@/lib/api/user-messages";
import { PageEyebrow } from "@/components/app/layout/PageEyebrow";
import { InlineErrorAlert } from "@/components/app/feedback/InlineErrorAlert";
import { cn } from "@/lib/utils";

const ChangePasswordSchema = z
  .object({
    newPassword: z
      .string()
      .min(12, "Password must be at least 12 characters")
      .max(128, "Password must be at most 128 characters"),
    confirmPassword: z.string().min(12, "Password must be at least 12 characters"),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

type ChangePasswordValues = z.infer<typeof ChangePasswordSchema>;

/**
 * Forced password change for sessions where `mustChangePassword === true`.
 * Submits via the live auth API, refreshes the session, and routes to the
 * role's default landing surface.
 */
export function ChangePasswordPage() {
  const { session, isLoading, signIn, signOut } = useSession();
  const navigate = useNavigate();
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [showNewPassword, setShowNewPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const form = useForm<ChangePasswordValues>({
    resolver: zodResolver(ChangePasswordSchema),
    defaultValues: { newPassword: "", confirmPassword: "" },
    mode: "onBlur",
  });

  // `useWatch` subscribes without returning a fresh function each render, so
  // the React Compiler can still memoize this component (`form.watch` cannot).
  // Being a hook, it has to sit above the early returns below.
  const newPasswordValue = useWatch({ control: form.control, name: "newPassword" });
  const confirmPasswordValue = useWatch({ control: form.control, name: "confirmPassword" });

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
        <span className="sr-only">Loading session</span>
      </div>
    );
  }

  if (!session) {
    return <Navigate to="/login" replace />;
  }

  if (!session.user.mustChangePassword) {
    return <Navigate to={defaultLandingFor(session.user.role)} replace />;
  }

  async function onSubmit(values: ChangePasswordValues): Promise<void> {
    setSubmitError(null);
    try {
      const next = await completePasswordChange({ newPassword: values.newPassword });
      signIn(next);
      navigate(defaultLandingFor(next.user.role), { replace: true });
    } catch (err) {
      const message = mapApiErrorMessage(err, "Could not update password.");
      setSubmitError(message);
    }
  }

  async function handleSignOut(): Promise<void> {
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <main className="bg-background flex min-h-screen flex-col items-center justify-center p-6">
      <div className="w-full max-w-md">
        <header className="mb-6 flex flex-col items-center gap-3 text-center">
          <span
            aria-hidden="true"
            className="bg-primary text-primary-foreground rounded-control inline-flex h-10 w-10 items-center justify-center"
          >
            <KeyRound className="h-5 w-5" />
          </span>
          <div>
            <PageEyebrow className="mb-1">Account security</PageEyebrow>
            <h1 className="text-foreground text-2xl leading-8 font-semibold tracking-tight">
              Set a new password
            </h1>
            <p className="text-foreground-muted mt-1 text-sm">
              Signed in as <span className="font-medium">{session.user.username}</span>. Choose a
              new password to continue.
            </p>
          </div>
        </header>

        <InlineErrorAlert message={submitError} className="mb-4" />

        <FormShell
          form={form}
          onSubmit={onSubmit}
          className="gap-5"
          aria-label="Change password"
          errorSummaryLabel="There are errors in this form"
        >
          <FormField
            control={form.control}
            name="newPassword"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>New password</FormLabel>
                <div className="relative">
                  <FormControl>
                    <Input
                      type={showNewPassword ? "text" : "password"}
                      autoComplete="new-password"
                      className="pr-10"
                      {...field}
                    />
                  </FormControl>
                  <button
                    type="button"
                    onClick={() => setShowNewPassword((prev) => !prev)}
                    disabled={form.formState.isSubmitting || newPasswordValue.length === 0}
                    aria-label={showNewPassword ? "Hide new password" : "Show new password"}
                    aria-pressed={showNewPassword}
                    className={cn(
                      "text-foreground-muted hover:text-foreground focus-visible:ring-ring rounded-r-control absolute inset-y-0 right-0 inline-flex h-full w-10 items-center justify-center transition-colors outline-none focus-visible:ring-2",
                      "disabled:pointer-events-none disabled:opacity-40",
                    )}
                  >
                    {showNewPassword ? (
                      <EyeOff aria-hidden="true" className="h-4 w-4" />
                    ) : (
                      <Eye aria-hidden="true" className="h-4 w-4" />
                    )}
                  </button>
                </div>
                <FormDescription>
                  At least 12 characters (matches the server password policy).
                </FormDescription>
                <FormMessage />
              </FormItem>
            )}
          />

          <FormField
            control={form.control}
            name="confirmPassword"
            render={({ field }) => (
              <FormItem required>
                <FormLabel>Confirm new password</FormLabel>
                <div className="relative">
                  <FormControl>
                    <Input
                      type={showConfirmPassword ? "text" : "password"}
                      autoComplete="new-password"
                      className="pr-10"
                      {...field}
                    />
                  </FormControl>
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword((prev) => !prev)}
                    disabled={form.formState.isSubmitting || confirmPasswordValue.length === 0}
                    aria-label={
                      showConfirmPassword ? "Hide confirm password" : "Show confirm password"
                    }
                    aria-pressed={showConfirmPassword}
                    className={cn(
                      "text-foreground-muted hover:text-foreground focus-visible:ring-ring rounded-r-control absolute inset-y-0 right-0 inline-flex h-full w-10 items-center justify-center transition-colors outline-none focus-visible:ring-2",
                      "disabled:pointer-events-none disabled:opacity-40",
                    )}
                  >
                    {showConfirmPassword ? (
                      <EyeOff aria-hidden="true" className="h-4 w-4" />
                    ) : (
                      <Eye aria-hidden="true" className="h-4 w-4" />
                    )}
                  </button>
                </div>
                <FormMessage />
              </FormItem>
            )}
          />

          <Button type="submit" disabled={form.formState.isSubmitting} className="mt-2">
            {form.formState.isSubmitting ? (
              <>
                <Loader2 aria-hidden="true" className="h-4 w-4 animate-spin" />
                <span>Saving…</span>
              </>
            ) : (
              <span>Update password</span>
            )}
          </Button>

          <Button
            type="button"
            variant="link"
            disabled={form.formState.isSubmitting}
            onClick={() => void handleSignOut()}
            className="text-foreground-muted h-auto p-0 text-sm"
          >
            Sign out and return to login
          </Button>
        </FormShell>
      </div>
    </main>
  );
}

export default ChangePasswordPage;
export const Component = ChangePasswordPage;
