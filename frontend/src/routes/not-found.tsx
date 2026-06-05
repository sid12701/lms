import { useNavigate } from "react-router-dom";
import { EmptyState } from "@/components/app/feedback/EmptyState";
import { Compass } from "lucide-react";

export function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <div className="flex min-h-screen items-center justify-center p-6">
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
        action={{ label: "Back to home", onClick: () => navigate("/home") }}
      />
    </div>
  );
}

export const Component = NotFoundPage;
