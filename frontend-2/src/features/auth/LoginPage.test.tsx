import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { LoginPage } from "./LoginPage";
import { USER_OPS_ADMIN } from "@/mocks/db/seed";
import type { Session } from "@/mocks/api/auth";

const adminSession: Session = {
  user: {
    id: USER_OPS_ADMIN,
    username: "ops.admin",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: false,
  },
  accessToken: "mock.token.value",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

const loginMock = vi.fn();

vi.mock("@/mocks/api", async () => {
  const real = await vi.importActual<typeof import("@/mocks/api")>("@/mocks/api");
  return {
    ...real,
    auth: {
      ...real.auth,
      login: (...args: Parameters<typeof real.auth.login>) => loginMock(...args),
    },
  };
});

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <SessionProvider skipBootstrap initialSession={null}>
        <TooltipProvider delayDuration={0}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/home" element={<div data-testid="home">Home</div>} />
          </Routes>
        </TooltipProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  loginMock.mockReset().mockResolvedValue(adminSession);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("LoginPage", () => {
  it("renders one card per seed user (6 cards)", () => {
    renderLogin();
    expect(screen.getByLabelText(/Sign in as ops\.admin/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sign in as ops\.user/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sign in as product\.admin/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sign in as lsp\.read/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sign in as lsp\.write/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Sign in as temp\.user/)).toBeInTheDocument();
  });

  it("calls auth.login and routes to /home on click", async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Sign in as ops\.admin/));
    await waitFor(() => {
      expect(loginMock).toHaveBeenCalledWith({ username: "ops.admin", password: "demo" });
    });
    expect(await screen.findByTestId("home")).toBeInTheDocument();
  });

  it("surfaces a sign-in error when the API rejects", async () => {
    loginMock.mockReset().mockRejectedValueOnce(new Error("invalid credentials"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Sign in as ops\.admin/));
    expect(await screen.findByRole("alert")).toHaveTextContent(/invalid credentials/i);
  });
});
