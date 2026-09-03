import type { ReactElement, ReactNode } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useSession } from "@/features/auth/use-session";
import { defaultLandingFor, isInternalUser, isLspUiUser } from "@/lib/role-gates";
import { formatPermissionDeniedDescription } from "@/lib/api/user-messages";
import { PermissionDeniedState } from "@/components/app/feedback/PermissionDeniedState";
import { RouteFallback } from "@/routes/route-fallback";
import type { Role } from "@/types";

/** Redirect to /login when there is no session. */
export function RequireAuth({ children }: { children: ReactNode }): ReactElement {
  const { session, isLoading } = useSession();
  const location = useLocation();
  // RequireAuth renders before AppShell mounts, so this fallback is the
  // whole document — standalone gives it a main landmark for axe.
  if (isLoading) return <RouteFallback standalone />;
  if (!session) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (session.user.mustChangePassword && location.pathname !== "/change-password") {
    return <Navigate to="/change-password" replace />;
  }
  return <>{children}</>;
}

/**
 * 403 surface shown when a signed-in user lands on a route their role can't
 * access. Always offers the same "go to your home" / "go back" escape hatches;
 * callers vary only the wording and the allow-list used to explain the gate.
 */
function PermissionDeniedRoute({
  title,
  currentRole,
  allowedRoles,
  actionLabel,
}: {
  title: string;
  currentRole: Role;
  allowedRoles: readonly string[];
  actionLabel: string;
}): ReactElement {
  const navigate = useNavigate();
  return (
    <div className="flex min-h-[60vh] items-center justify-center p-6">
      <PermissionDeniedState
        title={title}
        description={formatPermissionDeniedDescription(currentRole, allowedRoles)}
        action={{ label: actionLabel, onClick: () => navigate(defaultLandingFor(currentRole)) }}
        secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
      />
    </div>
  );
}

/** Internal-only routes. LSP users see a permission explanation. */
export function RequireInternal({ children }: { children: ReactNode }): ReactElement {
  const { session } = useSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!isInternalUser(session.user.role)) {
    return (
      <PermissionDeniedRoute
        title="Internal workspace only"
        currentRole={session.user.role}
        allowedRoles={["SYSTEM_ADMIN", "OPS_USER", "PRODUCT_ADMIN"]}
        actionLabel="Go to my loans"
      />
    );
  }
  return <>{children}</>;
}

/** LSP-only routes. Internal users see a permission explanation. */
export function RequireLsp({ children }: { children: ReactNode }): ReactElement {
  const { session } = useSession();
  if (!session) return <Navigate to="/login" replace />;
  if (!isLspUiUser(session.user.role)) {
    return (
      <PermissionDeniedRoute
        title="LSP workspace only"
        currentRole={session.user.role}
        allowedRoles={["LSP_UI_READ", "LSP_UI_WRITE"]}
        actionLabel="Go to workspace home"
      />
    );
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
      <PermissionDeniedRoute
        title="You don't have access to this page"
        currentRole={session.user.role}
        allowedRoles={roles}
        actionLabel="Go to your home"
      />
    );
  }
  return <>{children}</>;
}
