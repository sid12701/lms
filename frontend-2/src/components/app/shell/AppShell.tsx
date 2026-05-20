import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { Dialog, DialogContent, DialogTitle } from "@/components/ui/dialog";
import { Sidebar } from "./Sidebar";
import { TopBar } from "./TopBar";
import { BreadcrumbBar } from "./BreadcrumbBar";
import { resolveBreadcrumbLabel } from "./breadcrumb-labels";
import { cn } from "@/lib/utils";

export interface AppShellProps {
  /** Outlet content (route-rendered page). */
  children: ReactNode;
  /** Optional right-rail slot — surfaces per detail page (D6, 288px ≥ xl). */
  rightRail?: ReactNode;
  className?: string;
}

// Tailwind tier breakpoints (px). Matches the project's @theme defaults.
const LG_PX = 1024;
const XL_PX = 1280;

function useViewportTier(): "mobile" | "compact" | "wide" {
  const compute = (): "mobile" | "compact" | "wide" => {
    if (typeof window === "undefined") return "wide";
    const w = window.innerWidth;
    if (w >= XL_PX) return "wide";
    if (w >= LG_PX) return "compact";
    return "mobile";
  };
  const [tier, setTier] = useState<"mobile" | "compact" | "wide">(compute);
  useEffect(() => {
    if (typeof window === "undefined") return;
    const onResize = () => setTier(compute());
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return tier;
}

/**
 * The authenticated app frame. Composes:
 *
 *  - A skip-to-main-content link (focus-revealed; first focusable in the tree).
 *  - Persistent left `Sidebar` (264px ≥ xl, icon-only at lg–xl, slide-over < lg).
 *  - Sticky `TopBar` (56px) with command-palette trigger, scope chip, user menu.
 *  - `BreadcrumbBar` derived from the route.
 *  - The route `Outlet` (passed as children).
 *  - Optional `rightRail` (288px ≥ xl, hidden below).
 *  - A polite `aria-live` region announcing the resolved page label on each
 *    route change (helps screen-reader users notice client-side navigation).
 *
 * Mobile (< lg) uses a Dialog-portal as the slide-over for the sidebar drawer
 * (no Sheet primitive ships in the existing UI kit). The sidebar is rendered
 * once per render path (desktop OR mobile-drawer) so the resulting a11y tree
 * has exactly one `aside` with the "Primary navigation" landmark name.
 */
export function AppShell({ children, rightRail, className }: AppShellProps) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const tier = useViewportTier();
  const { pathname } = useLocation();

  // Resolve a friendly page label for the live region from the last meaningful
  // segment of the pathname (e.g. "/loan-applications/:id" → "Detail").
  const liveLabel = useMemo(() => {
    const segments = pathname.split("/").filter(Boolean);
    if (segments.length === 0) return "Home page";
    const last = segments[segments.length - 1] ?? "";
    return `${resolveBreadcrumbLabel(last)} page`;
  }, [pathname]);

  return (
    <div data-slot="app-shell" className={cn("bg-background flex min-h-screen", className)}>
      {/*
        Skip-to-main link — visually hidden until focused. Anchor (not Button)
        is the canonical pattern for skip links because the target is a
        fragment identifier (#main) rather than a click action.
      */}
      <a
        href="#main"
        data-slot="skip-to-main"
        className={cn(
          "sr-only",
          "focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50",
          "focus:rounded focus:bg-surface focus:px-3 focus:py-2 focus:text-sm focus:shadow-e2",
          "focus:outline focus:outline-2 focus:outline-[var(--color-ring)]",
        )}
      >
        Skip to main content
      </a>

      {tier !== "mobile" ? <Sidebar collapsed={tier === "compact"} /> : null}

      <div className="flex min-w-0 flex-1 flex-col overflow-x-clip">
        <TopBar onOpenMobileNav={tier === "mobile" ? () => setMobileNavOpen(true) : undefined} />
        <BreadcrumbBar />

        <div className="flex min-h-0 flex-1">
          <main id="main" className="min-w-0 flex-1 overflow-y-auto" tabIndex={-1}>
            {children}
          </main>
          {rightRail ? (
            <aside
              aria-label="Detail context"
              className="bg-surface hidden w-72 shrink-0 border-l border-[var(--color-border)] xl:block"
            >
              {rightRail}
            </aside>
          ) : null}
        </div>
      </div>

      {/* Route-change announcer — polite live region updates on navigation. */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-slot="route-announcer"
        className="sr-only"
      >
        {liveLabel}
      </div>

      {/* Mobile slide-over (< lg). Dialog handles focus trap + Escape. */}
      {tier === "mobile" ? (
        <Dialog open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
          <DialogContent
            showCloseButton={false}
            className="top-0 left-0 m-0 h-screen max-w-72 translate-x-0 translate-y-0 rounded-none border-0 p-0 sm:max-w-72"
          >
            <DialogTitle className="sr-only">Navigation</DialogTitle>
            <Sidebar onNavigate={() => setMobileNavOpen(false)} className="flex h-full" />
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  );
}
