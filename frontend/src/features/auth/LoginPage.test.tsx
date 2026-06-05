import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { LoginPage } from "./LoginPage";
import type { Session } from "@/features/auth/session-types";

const loginMock = vi.fn();
const loginPresetForRoleMock = vi.fn();
const toastErrorMock = vi.fn();

vi.mock("@/features/auth/auth-service", () => ({
  login: (...args: unknown[]) => loginMock(...args),
}));

vi.mock("@/features/auth/login-role-presets", async () => {
  const actual = await vi.importActual<typeof import("@/features/auth/login-role-presets")>(
    "@/features/auth/login-role-presets",
  );
  return {
    ...actual,
    loginPresetForRole: (...args: unknown[]) => loginPresetForRoleMock(...args),
  };
});

vi.mock("sonner", () => ({
  toast: {
    error: (...args: unknown[]) => toastErrorMock(...args),
  },
}));

function sessionFor(role: Session["user"]["role"], id: string, username: string): Session {
  return {
    user: {
      id,
      username,
      role,
      lspId:
        role === "LSP_UI_READ" || role === "LSP_UI_WRITE"
          ? "00000000-0000-4000-8000-000000000099"
          : null,
      mustChangePassword: false,
    },
    accessToken: "live.token.value",
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
          </Routes>
        </TooltipProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  loginMock
    .mockReset()
    .mockResolvedValue(
      sessionFor("SYSTEM_ADMIN", "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa", "ops.admin"),
    );
  loginPresetForRoleMock.mockReset().mockImplementation((role: string) => {
    const presets: Record<string, { username: string; password: string } | null> = {
      SYSTEM_ADMIN: { username: "ops.admin", password: "ChangeMe123!" },
      OPS_USER: { username: "ops.reviewer1", password: "DemoPass123!" },
      PRODUCT_ADMIN: { username: "product.owner", password: "DemoPass123!" },
      LSP_UI_READ: { username: "lsp.read1", password: "DemoPass123!" },
      LSP_UI_WRITE: { username: "lsp.write1", password: "DemoPass123!" },
    };
    return presets[role] ?? null;
  });
  toastErrorMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("LoginPage", () => {
  it("lists five role quick-fill cards", () => {
    renderLogin();
    expect(screen.getByLabelText(/Fill credentials for System administrator/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Fill credentials for Operations user/i)).toBeInTheDocument();
    expect(
      screen.getByLabelText(/Fill credentials for Product administrator/i),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText(/Fill credentials for LSP user \(read-only\)/i),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText(/Fill credentials for LSP user \(read\/write\)/i),
    ).toBeInTheDocument();
  });

  it("fills username and password when a role with a configured user is selected", async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for System administrator/i));
    expect(screen.getByLabelText(/^Username$/i)).toHaveValue("ops.admin");
    expect(screen.getByLabelText(/^Password$/i)).toHaveValue("ChangeMe123!");
  });

  it("toasts when no user is configured for the selected role", async () => {
    loginPresetForRoleMock.mockReturnValueOnce(null);
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for Operations user/i));
    expect(toastErrorMock).toHaveBeenCalledWith("No user exists for this role.");
    expect(screen.getByLabelText(/^Username$/i)).toHaveValue("");
  });

  it("routes SYSTEM_ADMIN to /home after sign-in", async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for System administrator/i));
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    await waitFor(() =>
      expect(loginMock).toHaveBeenCalledWith({
        username: "ops.admin",
        password: "ChangeMe123!",
      }),
    );
    expect(await screen.findByTestId("home")).toBeInTheDocument();
  });

  it("routes OPS_USER to /loan-applications after sign-in", async () => {
    loginMock.mockResolvedValueOnce(
      sessionFor("OPS_USER", "aaaaaaaa-2222-4aaa-8aaa-aaaaaaaaaaaa", "ops.reviewer1"),
    );
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for Operations user/i));
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("loan-applications")).toBeInTheDocument();
  });

  it("routes PRODUCT_ADMIN to /products after sign-in", async () => {
    loginMock.mockResolvedValueOnce(
      sessionFor("PRODUCT_ADMIN", "aaaaaaaa-3333-4aaa-8aaa-aaaaaaaaaaaa", "product.owner"),
    );
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for Product administrator/i));
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("products")).toBeInTheDocument();
  });

  it("routes LSP_UI_READ to /my-loans after sign-in", async () => {
    loginMock.mockResolvedValueOnce(
      sessionFor("LSP_UI_READ", "aaaaaaaa-4444-4aaa-8aaa-aaaaaaaaaaaa", "lsp.read1"),
    );
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for LSP user \(read-only\)/i));
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByTestId("my-loans")).toBeInTheDocument();
  });

  it("surfaces a sign-in error when login rejects", async () => {
    loginMock.mockRejectedValueOnce(new Error("invalid credentials"));
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByLabelText(/Fill credentials for System administrator/i));
    await user.clear(screen.getByLabelText(/^Password$/i));
    await user.type(screen.getByLabelText(/^Password$/i), "wrong");
    await user.click(screen.getByRole("button", { name: /^sign in$/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/invalid credentials/i);
  });
});
