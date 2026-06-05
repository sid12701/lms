import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SessionProvider } from "@/features/auth/session-context";
import { ChangePasswordPage } from "./ChangePasswordPage";
import type { Session } from "@/features/auth/session-types";
import { tempPasswordSession } from "@/test/session-fixtures";

const settledSession: Session = {
  ...tempPasswordSession,
  user: { ...tempPasswordSession.user, mustChangePassword: false },
};

const completePasswordChangeMock = vi.fn();

vi.mock("@/features/auth/auth-service", () => ({
  completePasswordChange: (...args: unknown[]) => completePasswordChangeMock(...args),
}));

function renderForm(initial: Session | null = tempPasswordSession) {
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
