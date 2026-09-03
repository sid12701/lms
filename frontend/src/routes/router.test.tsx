import type { ReactNode } from "react";
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Routes, Route, Navigate } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { LandingRedirect } from "./landing-redirect";
import { RequireAuth, RequireInternal, RequireLsp, RequireRole } from "./guards";
import { defaultLandingFor } from "@/lib/role-gates";
import type { Session } from "@/features/auth/session-types";
import {
  adminSession,
  lspReadSession,
  tempPasswordSession,
  TEST_OPS_USER_ID,
} from "@/test/session-fixtures";

function renderWithSession(initial: Session | null, initialEntry: string, routesEl: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SessionProvider skipBootstrap initialSession={initial}>
        <TooltipProvider delayDuration={0}>{routesEl}</TooltipProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe("route smoke tests", () => {
  it("LandingRedirect routes unauthenticated users to /login", () => {
    renderWithSession(
      null,
      "/",
      <Routes>
        <Route path="/" element={<LandingRedirect />} />
        <Route path="/login" element={<div data-testid="login">login</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("login")).toBeInTheDocument();
  });

  it("LandingRedirect routes an internal user to /home", () => {
    renderWithSession(
      adminSession,
      "/",
      <Routes>
        <Route path="/" element={<LandingRedirect />} />
        <Route path="/home" element={<div data-testid="home">home</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("home")).toBeInTheDocument();
    expect(defaultLandingFor("SYSTEM_ADMIN")).toBe("/home");
  });

  it("LandingRedirect routes an LSP user to /my-loans", () => {
    renderWithSession(
      lspReadSession,
      "/",
      <Routes>
        <Route path="/" element={<LandingRedirect />} />
        <Route path="/my-loans" element={<div data-testid="my-loans">Loan applications</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("my-loans")).toBeInTheDocument();
    expect(defaultLandingFor("LSP_UI_READ")).toBe("/my-loans");
  });

  it("RequireAuth bounces a user with mustChangePassword to /change-password", () => {
    renderWithSession(
      tempPasswordSession,
      "/home",
      <Routes>
        <Route
          path="/home"
          element={
            <RequireAuth>
              <div data-testid="home">home</div>
            </RequireAuth>
          }
        />
        <Route path="/change-password" element={<div data-testid="cp">change password</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("cp")).toBeInTheDocument();
  });

  it("RequireInternal shows a permission explanation for an LSP user (NAV-02)", () => {
    renderWithSession(
      lspReadSession,
      "/loan-applications",
      <Routes>
        <Route
          path="/loan-applications"
          element={
            <RequireInternal>
              <div data-testid="apps">apps</div>
            </RequireInternal>
          }
        />
        <Route path="/my-loans" element={<div data-testid="my-loans">Loan applications</div>} />
      </Routes>,
    );
    expect(screen.getByText("Internal workspace only")).toBeInTheDocument();
    expect(screen.queryByTestId("apps")).not.toBeInTheDocument();
    expect(screen.queryByTestId("my-loans")).not.toBeInTheDocument();
  });

  it("RequireLsp shows a permission explanation for SYSTEM_ADMIN (NAV-02)", () => {
    renderWithSession(
      adminSession,
      "/my-loans",
      <Routes>
        <Route
          path="/my-loans"
          element={
            <RequireLsp>
              <div data-testid="my-loans">Loan applications</div>
            </RequireLsp>
          }
        />
        <Route path="/home" element={<div data-testid="home">home</div>} />
      </Routes>,
    );
    expect(screen.getByText("LSP workspace only")).toBeInTheDocument();
    expect(screen.queryByTestId("my-loans")).not.toBeInTheDocument();
    expect(screen.queryByTestId("home")).not.toBeInTheDocument();
  });

  it("RequireLsp shows a permission explanation for OPS_USER (NAV-02)", () => {
    const opsSession: Session = {
      user: {
        id: TEST_OPS_USER_ID,
        username: "ops.user",
        role: "OPS_USER",
        lspId: null,
        mustChangePassword: false,
      },
      accessToken: "mock.token",
      expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    };
    renderWithSession(
      opsSession,
      "/my-loans",
      <Routes>
        <Route
          path="/my-loans"
          element={
            <RequireLsp>
              <div data-testid="my-loans">Loan applications</div>
            </RequireLsp>
          }
        />
        <Route
          path="/loan-applications"
          element={<div data-testid="loan-applications">apps</div>}
        />
      </Routes>,
    );
    expect(screen.getByText("LSP workspace only")).toBeInTheDocument();
    expect(screen.queryByTestId("my-loans")).not.toBeInTheDocument();
    expect(screen.queryByTestId("loan-applications")).not.toBeInTheDocument();
  });

  it("RequireRole denies PRODUCT_ADMIN from ops-only workspaces", () => {
    const productSession: Session = {
      user: {
        id: "aaaaaaaa-3333-4aaa-8aaa-aaaaaaaaaaaa",
        username: "product.owner",
        role: "PRODUCT_ADMIN",
        lspId: null,
        mustChangePassword: false,
      },
      accessToken: "mock.token",
      expiresAt: new Date(Date.now() + 3600_000).toISOString(),
    };
    renderWithSession(
      productSession,
      "/loan-applications",
      <Routes>
        <Route
          path="/loan-applications"
          element={
            <RequireRole roles={["SYSTEM_ADMIN", "OPS_USER"]}>
              <div data-testid="loan-applications">apps</div>
            </RequireRole>
          }
        />
      </Routes>,
    );
    expect(screen.getByText("You don't have access to this page")).toBeInTheDocument();
    expect(screen.queryByTestId("loan-applications")).not.toBeInTheDocument();
  });

  it("createAppRouter compiles the route tree without throwing", async () => {
    const { createAppRouter } = await import("./router");
    const router = createAppRouter();
    expect(router.routes.length).toBeGreaterThan(0);
    // Sanity: a Navigate component is composable inside MemoryRouter.
    render(
      <MemoryRouter initialEntries={["/x"]}>
        <Routes>
          <Route path="/x" element={<Navigate to="/y" replace />} />
          <Route path="/y" element={<div data-testid="y">y</div>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByTestId("y")).toBeInTheDocument();
  });
});
