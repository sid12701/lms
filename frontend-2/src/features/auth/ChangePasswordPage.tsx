import { useState } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { KeyRound, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { auth } from "@/mocks/api";
import { useSession } from "@/features/auth/session-context";
import { defaultLandingFor } from "@/lib/role-gates";
import { PageEyebrow } from "@/components/app/layout/PageEyebrow";

const ChangePasswordSchema = z
  .object({
    newPassword: z.string().min(8, "Password must be at least 8 characters"),
    confirmPassword: z.string().min(8, "Password must be at least 8 characters"),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

type ChangePasswordValues = z.infer<typeof ChangePasswordSchema>;

/**
 * Forced password change for sessions where `mustChangePassword === true`.
 * Submits via the mock auth handler, refreshes the session, and routes to the
 * role's default landing surface.
 */
export function ChangePasswordPage() {
  const { session, isLoading, refresh } = useSession();
  const navigate = useNavigate();
  const [submitError, setSubmitError] = useState<string | null>(null);

  const form = useForm<ChangePasswordValues>({
    resolver: zodResolver(ChangePasswordSchema),
    defaultValues: { newPassword: "", confirmPassword: "" },
    mode: "onBlur",
  });

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
      await auth.completePasswordChange({ newPassword: values.newPassword });
      await refresh();
      // After refresh, mustChangePassword should be false. Navigate to landing.
      if (session) {
        navigate(defaultLandingFor(session.user.role), { replace: true });
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not update password";
      setSubmitError(message);
    }
  }

  return (
    <main className="bg-background flex min-h-screen flex-col items-center justify-center p-6">
      <div className="w-full max-w-md">
        <header className="mb-6 flex flex-col items-center gap-3 text-center">
          <span
            aria-hidden="true"
            className="bg-brand-700 text-primary-foreground inline-flex h-10 w-10 items-center justify-center rounded-md"
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

        {submitError ? (
          <div
            role="alert"
            className="border-destructive/30 bg-destructive/5 text-destructive mb-4 rounded-md border px-4 py-3 text-sm"
          >
            {submitError}
          </div>
        ) : null}

        <Form {...form}>
          <form
            onSubmit={form.handleSubmit(onSubmit)}
            className="flex flex-col gap-5"
            aria-label="Change password"
            noValidate
          >
            <FormField
              control={form.control}
              name="newPassword"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>New password</FormLabel>
                  <FormControl>
                    <Input type="password" autoComplete="new-password" {...field} />
                  </FormControl>
                  <FormDescription>Minimum 8 characters.</FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="confirmPassword"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Confirm new password</FormLabel>
                  <FormControl>
                    <Input type="password" autoComplete="new-password" {...field} />
                  </FormControl>
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
          </form>
        </Form>
      </div>
    </main>
  );
}

export default ChangePasswordPage;
export const Component = ChangePasswordPage;
