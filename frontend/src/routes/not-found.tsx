import { useNavigate } from "react-router-dom";
import { useSession } from "@/features/auth/session-context";
import { defaultLandingFor } from "@/lib/role-gates";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { Compass } from "lucide-react";
import { cn } from "@/lib/utils";

export interface NotFoundPageProps {
  /** When true, render for the authenticated shell (no full-viewport centering). */
  inShell?: boolean;
}

export function NotFoundPage({ inShell = false }: NotFoundPageProps) {
  const navigate = useNavigate();
  const { session } = useSession();
  const homeTarget = session ? defaultLandingFor(session.user.role) : "/login";
  return (
    <div
      className={cn(
        "flex items-center justify-center p-6",
        inShell ? "min-h-[50vh]" : "min-h-screen",
      )}
    >
      {/*
        EmptyState renders the title as a <p> (component-level decision; it's
        used for inline empty regions far more often than for whole pages).
        The 404 ROUTE specifically needs a single page-level <h1> for SR users
        and the document outline, so we emit an sr-only h1 here. The visible
        EmptyState title remains for sighted users.
      */}
      <h1 className="sr-only">Page not found</h1>
      <EmptyState
        icon={Compass}
        title="Page not found"
        description="The page you are looking for does not exist or has been moved."
        action={{ label: "Back to home", onClick: () => navigate(homeTarget) }}
        secondaryAction={{ label: "Go back", onClick: () => navigate(-1) }}
      />
    </div>
  );
}

export const Component = NotFoundPage;
