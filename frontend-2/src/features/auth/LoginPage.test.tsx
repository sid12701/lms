import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { LoginPage } from "./LoginPage";
import type { Session } from "@/mocks/api/auth";
import {
  USER_OPS_ADMIN,
  USER_OPS_USER,
  USER_PRODUCT_ADMIN,
  USER_LSP_READ,
} from "@/mocks/db/seed";

const loginMock = vi.fn();

vi.mock("@/features/auth/auth-service", () => ({
  login: (...args: unknown[]) => loginMock(...args),
}));

function sessionFor(
  role: Session["user"]["role"],
  id: string,
  username: string,
): Session {
  return {
    user: {
      id,
      username,
      role,
      lspId: role === "LSP_UI_READ" || role === "LSP_UI_WRITE" ? "00000000-0000-4000-8000-000000000099" : null,
      mustChangePassword: false,
    },
    accessToken: "mock.token.value",
    expiresAt: new Date(Date.now() + 3600_000).toISOString(),
  };
}

function renderLogin() {
  return render(
    <MemoryRouter initialEntries={["/login"]}>
      <SessionProvider skipBootstrap initialSession={null}>
        <TooltipProvider delayDuration={0}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/home" element={<div data-testid="home">Home</div>} />
            <Route
              path="/loan-applications"
              element={<div data-testid="loan-applications">Applications</div>}
            />
            <Route path="/products" element={<div data-testid="products">Products</div>} />
            <Route path="/my-loans" element={<div data-testid="my-loans">My loans</div>} />
            <Route
              path="/change-password"
              element={<div data-testid="change-password">Change password</div>}
            />
          </Routes>
        </TooltipProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  loginMock.mockReset().mockResolvedValue(sessionFor("SYSTEM_ADMIN", USER_OPS_ADMIN, "ops.admin"));
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("LoginPage (Gap #8 post-login redirect)", () => {
  it("lists six dev prefill cards for seeded backend accounts", () => {
    renderLogin();
    expect(screen.getByLabelText(/Prefill ops\.admin/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prefill ops\.user/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prefill product\.admin/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prefill lsp\.read/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prefill lsp\.write/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Prefill temp\.user/i)).toBeInTheDocument();
  });

  it("routes SYSTEM_ADMIN to /home after sign-in", async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Prefill ops\.admin/i));
    await user.type(screen.getByLabelText(/^Password$/i), "demo");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    await waitFor(() => expect(loginMock).toHaveBeenCalledWith({ username: "ops.admin", password: "demo" }));
    expect(await screen.findByTestId("home")).toBeInTheDocument();
  });

  it("routes OPS_USER to /loan-applications after sign-in", async () => {
    loginMock.mockResolvedValueOnce(sessionFor("OPS_USER", USER_OPS_USER, "ops.user"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Prefill ops\.user/i));
    await user.type(screen.getByLabelText(/^Password$/i), "demo");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("loan-applications")).toBeInTheDocument();
  });

  it("routes PRODUCT_ADMIN to /products after sign-in", async () => {
    loginMock.mockResolvedValueOnce(sessionFor("PRODUCT_ADMIN", USER_PRODUCT_ADMIN, "product.admin"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Prefill product\.admin/i));
    await user.type(screen.getByLabelText(/^Password$/i), "demo");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("products")).toBeInTheDocument();
  });

  it("routes LSP_UI_READ to /my-loans after sign-in", async () => {
    loginMock.mockResolvedValueOnce(sessionFor("LSP_UI_READ", USER_LSP_READ, "lsp.read"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Prefill lsp\.read/i));
    await user.type(screen.getByLabelText(/^Password$/i), "demo");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("my-loans")).toBeInTheDocument();
  });

  it("surfaces a sign-in error when login rejects", async () => {
    loginMock.mockRejectedValueOnce(new Error("invalid credentials"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Prefill ops\.admin/i));
    await user.type(screen.getByLabelText(/^Password$/i), "wrong");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/invalid credentials/i);
  });
});
