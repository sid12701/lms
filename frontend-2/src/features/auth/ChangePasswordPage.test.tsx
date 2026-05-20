import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { ChangePasswordPage } from "./ChangePasswordPage";
import { USER_TEMP } from "@/mocks/db/seed";
import type { Session } from "@/mocks/api/auth";

const tempSession: Session = {
  user: {
    id: USER_TEMP,
    username: "temp.user",
    role: "SYSTEM_ADMIN",
    lspId: null,
    mustChangePassword: true,
  },
  accessToken: "mock.token.value",
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
};

const settledSession: Session = {
  ...tempSession,
  user: { ...tempSession.user, mustChangePassword: false },
};

const completePasswordChangeMock = vi.fn();
const sessionApiMock = vi.fn();

vi.mock("@/mocks/api", async () => {
  const real = await vi.importActual<typeof import("@/mocks/api")>("@/mocks/api");
  return {
    ...real,
    auth: {
      ...real.auth,
      completePasswordChange: (...args: Parameters<typeof real.auth.completePasswordChange>) =>
        completePasswordChangeMock(...args),
      session: (...args: Parameters<typeof real.auth.session>) => sessionApiMock(...args),
    },
  };
});

function renderForm(initial: Session | null = tempSession) {
  return render(
    <MemoryRouter initialEntries={["/change-password"]}>
      <SessionProvider skipBootstrap initialSession={initial}>
        <TooltipProvider delayDuration={0}>
          <Routes>
            <Route path="/change-password" element={<ChangePasswordPage />} />
            <Route path="/home" element={<div data-testid="home">Home</div>} />
            <Route path="/login" element={<div data-testid="login">Login</div>} />
          </Routes>
        </TooltipProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  completePasswordChangeMock.mockReset().mockResolvedValue(settledSession);
  sessionApiMock.mockReset().mockResolvedValue(settledSession);
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("ChangePasswordPage", () => {
  it("redirects to /login when there is no session", () => {
    renderForm(null);
    expect(screen.getByTestId("login")).toBeInTheDocument();
  });

  it("surfaces a validation error when the two passwords do not match", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText(/^new password$/i), "longenough123");
    await user.type(screen.getByLabelText(/confirm new password/i), "different456");
    await user.click(screen.getByRole("button", { name: /update password/i }));
    expect(await screen.findByText(/Passwords do not match/i)).toBeInTheDocument();
  });

  it("surfaces a validation error when the password is too short", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.type(screen.getByLabelText(/^new password$/i), "short");
    await user.type(screen.getByLabelText(/confirm new password/i), "short");
    await user.click(screen.getByRole("button", { name: /update password/i }));
    expect((await screen.findAllByText(/at least 8 characters/i)).length).toBeGreaterThanOrEqual(1);
  });
});
