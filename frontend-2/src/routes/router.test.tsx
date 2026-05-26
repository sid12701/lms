import type { ReactNode } from "react";
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Routes, Route, Navigate } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { LandingRedirect } from "./landing-redirect";
import { RequireAuth, RequireInternal, RequireLsp } from "./guards";
import { defaultLandingFor } from "@/lib/role-gates";
import type { Session } from "@/mocks/api/auth";
import { USER_OPS_ADMIN, USER_OPS_USER, USER_LSP_READ, USER_TEMP, LSP_BHAW_DEMO } from "@/mocks/db/seed";

const adminSession: Session = {
  user: {
    id: USER_OPS_ADMIN,
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "mock.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

const lspSession: Session = {
  user: {
    id: USER_LSP_READ,
    username: "lsp.read",
    role: "LSP_UI_READ",
    lspId: LSP_BHAW_DEMO,
    mustChangePassword: false,
  },
  accessToken: "mock.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

const tempSession: Session = {
  user: {
    id: USER_TEMP,
    username: "temp.user",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: true,
  },
  accessToken: "mock.token",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

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
      lspSession,
      "/",
      <Routes>
        <Route path="/" element={<LandingRedirect />} />
        <Route path="/my-loans" element={<div data-testid="my-loans">my loans</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("my-loans")).toBeInTheDocument();
    expect(defaultLandingFor("LSP_UI_READ")).toBe("/my-loans");
  });

  it("RequireAuth bounces a user with mustChangePassword to /change-password", () => {
    renderWithSession(
      tempSession,
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

  it("RequireInternal redirects an LSP user to their default landing", () => {
    renderWithSession(
      lspSession,
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
        <Route path="/my-loans" element={<div data-testid="my-loans">my loans</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("my-loans")).toBeInTheDocument();
  });

  it("RequireLsp redirects SYSTEM_ADMIN to /home (Gap #8 default landing)", () => {
    renderWithSession(
      adminSession,
      "/my-loans",
      <Routes>
        <Route
          path="/my-loans"
          element={
            <RequireLsp>
              <div data-testid="my-loans">my loans</div>
            </RequireLsp>
          }
        />
        <Route path="/home" element={<div data-testid="home">home</div>} />
      </Routes>,
    );
    expect(screen.getByTestId("home")).toBeInTheDocument();
  });

  it("RequireLsp redirects OPS_USER to /loan-applications (Gap #8)", () => {
    const opsSession: Session = {
      user: {
        id: USER_OPS_USER,
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
              <div data-testid="my-loans">my loans</div>
            </RequireLsp>
          }
        />
        <Route
          path="/loan-applications"
          element={<div data-testid="loan-applications">apps</div>}
        />
      </Routes>,
    );
    expect(screen.getByTestId("loan-applications")).toBeInTheDocument();
  });

  it("createAppRouter compiles the route tree without throwing", async () => {
    const { createAppRouter } = await import("./router");
    const router = createAppRouter();
    expect(router).toBeDefined();
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
