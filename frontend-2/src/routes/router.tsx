/* eslint-disable react-refresh/only-export-components -- module co-exports a router factory + private layout components */
import { lazy, Suspense, type ComponentType, type LazyExoticComponent } from "react";
import { createBrowserRouter, Outlet, type RouteObject } from "react-router-dom";
import { Loader2 } from "lucide-react";
import { LandingRedirect } from "@/routes/landing-redirect";
import { NotFoundPage } from "@/routes/not-found";
import { RequireAuth, RequireInternal, RequireLsp, RequireRole } from "@/routes/guards";
import { AppShell } from "@/components/app/shell/AppShell";
import type { Role } from "@/types";

// ─── Role allow-lists per route (per IA in plan §4) ──────────────────────────

const INTERNAL_ALL: readonly Role[] = ["SYSTEM_ADMIN", "OPS_USER", "PRODUCT_ADMIN"] as const;
const SYSTEM_ADMIN_ONLY: readonly Role[] = ["SYSTEM_ADMIN"] as const;
const SYSTEM_ADMIN_OR_OPS: readonly Role[] = ["SYSTEM_ADMIN", "OPS_USER"] as const;
const PRODUCT_ADMIN_OR_SYSTEM: readonly Role[] = ["SYSTEM_ADMIN", "PRODUCT_ADMIN"] as const;
const ALL_AUTHENTICATED: readonly Role[] = [
  "SYSTEM_ADMIN",
  "OPS_USER",
  "PRODUCT_ADMIN",
  "LSP_UI_READ",
  "LSP_UI_WRITE",
] as const;
const LSP_UI_ALL: readonly Role[] = ["LSP_UI_READ", "LSP_UI_WRITE"] as const;

// ─── Lazy page components ────────────────────────────────────────────────────
//
// Each page module exports both a default and a named `Component`. The
// `lazyPage` helper resolves either, then wraps the dynamic import in
// React.lazy at module load time so the resulting component identity is
// stable across renders (this is what react-hooks/static-components requires).

interface PageModule {
  default?: ComponentType;
  Component?: ComponentType;
}

function lazyPage(load: () => Promise<PageModule>): LazyExoticComponent<ComponentType> {
  return lazy(async () => {
    const mod = await load();
    const Resolved = mod.Component ?? mod.default;
    if (!Resolved) {
      throw new Error("Lazy route module is missing a default or Component export");
    }
    return { default: Resolved };
  });
}

const HomePage = lazyPage(() => import("@/features/home/page"));
const LoanApplicationsPage = lazyPage(() => import("@/features/loan-applications/page"));
const LoanApplicationDetailPage = lazyPage(
  () => import("@/features/loan-applications/detail-page"),
);
const BorrowerDetailPage = lazyPage(() => import("@/features/borrowers/detail-page"));
const AlertsPage = lazyPage(() => import("@/features/alerts/page"));
const ReportsPage = lazyPage(() => import("@/features/reports/page"));
const LspsPage = lazyPage(() => import("@/features/lsps/page"));
const ProductsPage = lazyPage(() => import("@/features/products/page"));
const UsersPage = lazyPage(() => import("@/features/users/page"));
const ApiClientsPage = lazyPage(() => import("@/features/api-clients/page"));
const AuditPage = lazyPage(() => import("@/features/audit/page"));
const MyLoansPage = lazyPage(() => import("@/features/my-loans/page"));
const MyLoanDetailPage = lazyPage(() => import("@/features/my-loans/detail-page"));

function RouteFallback() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <Loader2 aria-hidden="true" className="text-foreground-muted h-5 w-5 animate-spin" />
      <span className="sr-only">Loading page</span>
    </div>
  );
}

function withSuspense(node: ComponentType) {
  const Comp = node;
  return (
    <Suspense fallback={<RouteFallback />}>
      <Comp />
    </Suspense>
  );
}

/**
 * The shared authenticated layout: gate on session, render the app shell,
 * and let the matched child route fill the outlet.
 */
function AuthenticatedLayout() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  );
}

/**
 * Build the route tree. Authenticated routes use lazy code-splitting per
 * feature folder; auth surfaces are eager because they're tiny and on the
 * critical bootstrap path.
 */
export function createAppRouter() {
  const routes: RouteObject[] = [
    { path: "/", element: <LandingRedirect /> },
    {
      path: "/login",
      lazy: async () => {
        const mod = await import("@/features/auth/LoginPage");
        return { Component: mod.LoginPage };
      },
    },
    {
      path: "/change-password",
      lazy: async () => {
        const mod = await import("@/features/auth/ChangePasswordPage");
        return { Component: mod.ChangePasswordPage };
      },
    },
    {
      element: <AuthenticatedLayout />,
      children: [
        // Home — dual-purpose (internal + LSP). The page branches on role.
        {
          path: "/home",
          element: <RequireRole roles={ALL_AUTHENTICATED}>{withSuspense(HomePage)}</RequireRole>,
        },

        // Internal-only routes ──────────────────────────────────────────────
        {
          path: "/loan-applications",
          element: (
            <RequireInternal>
              <RequireRole roles={INTERNAL_ALL}>{withSuspense(LoanApplicationsPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/loan-applications/:id",
          element: (
            <RequireInternal>
              <RequireRole roles={INTERNAL_ALL}>
                {withSuspense(LoanApplicationDetailPage)}
              </RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/borrowers/:id",
          element: (
            <RequireInternal>
              <RequireRole roles={INTERNAL_ALL}>{withSuspense(BorrowerDetailPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/alerts",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_OR_OPS}>{withSuspense(AlertsPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/reports",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_ONLY}>{withSuspense(ReportsPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/lsps",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_ONLY}>{withSuspense(LspsPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/products",
          element: (
            <RequireInternal>
              <RequireRole roles={PRODUCT_ADMIN_OR_SYSTEM}>
                {withSuspense(ProductsPage)}
              </RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/users",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_ONLY}>{withSuspense(UsersPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/api-clients",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_ONLY}>{withSuspense(ApiClientsPage)}</RequireRole>
            </RequireInternal>
          ),
        },
        {
          path: "/audit",
          element: (
            <RequireInternal>
              <RequireRole roles={SYSTEM_ADMIN_ONLY}>{withSuspense(AuditPage)}</RequireRole>
            </RequireInternal>
          ),
        },

        // LSP-only routes ───────────────────────────────────────────────────
        {
          path: "/my-loans",
          element: (
            <RequireLsp>
              <RequireRole roles={LSP_UI_ALL}>{withSuspense(MyLoansPage)}</RequireRole>
            </RequireLsp>
          ),
        },
        {
          path: "/my-loans/:id",
          element: (
            <RequireLsp>
              <RequireRole roles={LSP_UI_ALL}>{withSuspense(MyLoanDetailPage)}</RequireRole>
            </RequireLsp>
          ),
        },
      ],
    },
    { path: "*", element: <NotFoundPage /> },
  ];

  // DEV-only: components sandbox at /dev/components, no auth guards.
  if (import.meta.env.DEV) {
    routes.splice(routes.length - 1, 0, {
      path: "/dev/components",
      lazy: async () => {
        const mod = await import("@/features/dev/components-sandbox");
        return { Component: mod.ComponentsSandboxPage };
      },
    });
  }

  return createBrowserRouter(routes);
}
