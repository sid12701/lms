import { Navigate } from "react-router-dom";
import { useSession } from "@/features/auth/session-context";
import { RouteFallback } from "@/routes/route-fallback";
import { defaultLandingFor } from "@/lib/role-gates";

/**
 * Resolves "/" to the right landing route based on session state:
 * - unauthenticated → /login
 * - mustChangePassword → /change-password
 * - else → defaultLandingFor(role) (i.e. /home or /my-loans)
 */
export function LandingRedirect() {
  const { session, isLoading } = useSession();
  if (isLoading) return <RouteFallback standalone />;
  if (!session) return <Navigate to="/login" replace />;
  if (session.user.mustChangePassword) return <Navigate to="/change-password" replace />;
  return <Navigate to={defaultLandingFor(session.user.role)} replace />;
}
