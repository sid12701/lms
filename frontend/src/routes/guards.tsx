import type { ReactElement, ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useSession } from "@/features/auth/use-session";
import { isInternalUser, isLspUiUser, defaultLandingFor } from "@/lib/role-gates";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import type { Role } from "@/types";

/** Redirect to /login when there is no session. */
export function RequireAuth({ children }: { children: ReactNode }): ReactElement {
  const { session, isLoading } = useSession();
  const location = useLocation();
  if (isLoading) return <div aria-busy="true" />;
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (session.user.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

/** Internal-only routes. Redirects LSP users to their default landing. */
export function RequireInternal({ children }: { children: ReactNode }): ReactElement {
  const { session } = useSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!isInternalUser(session.user.role)) {
    return <Navigate to={defaultLandingFor(session.user.role)} replace />;
  }
  return <>{children}</>;
}

/** LSP-only routes. Redirects internal users to their default landing. */
export function RequireLsp({ children }: { children: ReactNode }): ReactElement {
  const { session } = useSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!isLspUiUser(session.user.role)) {
    return <Navigate to={defaultLandingFor(session.user.role)} replace />;
  }
  return <>{children}</>;
}

interface RequireRoleProps {
  roles: readonly Role[];
  children: ReactNode;
}

/** Render a 403 surface if the active role is not in the allow-list. */
export function RequireRole({ roles, children }: RequireRoleProps): ReactElement {
  const { session } = useSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!roles.includes(session.user.role)) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center p-6">
        <PermissionDeniedState
          currentRole={session.user.role}
          missingPermission={roles.join(" or ")}
        />
      </div>
    );
  }
  return <>{children}</>;
}
