import { Outlet } from "react-router-dom";
import { ErrorState } from "@/components/app/feedback/ErrorState";
import { useSession } from "@/features/auth/use-session";
import { RouteFallback } from "@/routes/route-fallback";

/**
 * Root layout route — providers live in {@link App} so session/theme/query context
 * wraps the router (including lazy auth pages) and survives Vite HMR cleanly.
 */
export function AppRoot() {
  const { isLoading, sessionRestoreError, refresh } = useSession();

  if (isLoading) return <RouteFallback standalone />;

  if (sessionRestoreError) {
    const invalidContext = sessionRestoreError === "CONTEXT_INVALID";
    return (
      <main className="flex min-h-screen items-center justify-center p-6">
        <h1 className="sr-only">Session unavailable</h1>
        <ErrorState
          title="We couldn't restore your session"
          description={
            invalidContext
              ? "Your sign-in was refreshed, but the workspace response was invalid. Try again, and contact support if the problem continues."
              : "Your session could not be verified right now. Check your connection and try again."
          }
          retry={{ label: "Retry", onClick: () => void refresh() }}
          className="w-full max-w-xl"
        />
      </main>
    );
  }

  return <Outlet />;
}
