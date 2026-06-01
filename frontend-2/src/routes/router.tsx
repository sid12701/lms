import { Suspense, type ComponentType } from "react";
import { createBrowserRouter, type RouteObject } from "react-router-dom";
import { LandingRedirect } from "@/routes/landing-redirect";
import { NotFoundPage } from "@/routes/not-found";
import { RequireInternal, RequireLsp, RequireRole } from "@/routes/guards";
import type { Role } from "@/types";
import { lazyPage } from "@/routes/lazy-page";
import { RouteFallback } from "@/routes/route-fallback";
import { AuthenticatedLayout } from "@/routes/authenticated-layout";

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

const HomePage = lazyPage(() => import("@/features/home/page"));
const LoanApplicationsPage = lazyPage(() => import("@/features/loan-applications/page"));
const LoanApplicationDetailPage = lazyPage(
  () => import("@/features/loan-applications/detail-page"),
);
const BorrowersPage = lazyPage(() => import("@/features/borrowers/page"));
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

function withSuspense(node: ComponentType) {
  const Comp = node;
  return (
    <Suspense fallback={<RouteFallback />}>
      <Comp />
    </Suspense>
  );
}

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
        {
          path: "/home",
          element: <RequireRole roles={ALL_AUTHENTICATED}>{withSuspense(HomePage)}</RequireRole>,
        },
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
          path: "/borrowers",
          element: (
            <RequireInternal>
              <RequireRole roles={INTERNAL_ALL}>{withSuspense(BorrowersPage)}</RequireRole>
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
