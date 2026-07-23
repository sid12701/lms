import { afterEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { SessionProvider } from "@/features/auth/session-provider";
import { refreshSession, SessionRestoreError } from "@/features/auth/auth-service";
import { AppRoot } from "@/routes/app-root";
import { adminSession } from "@/test/session-fixtures";

vi.mock("@/lib/api/session-storage", () => ({
  loadStoredSession: vi.fn(() => adminSession),
}));

vi.mock("@/features/auth/auth-service", async () => {
  const actual = await vi.importActual<typeof import("@/features/auth/auth-service")>(
    "@/features/auth/auth-service",
  );
  return {
    ...actual,
    refreshSession: vi.fn(),
    logout: vi.fn(),
  };
});

function renderProtectedApp() {
  return render(
    <MemoryRouter initialEntries={["/loan-applications"]}>
      <SessionProvider>
        <Routes>
          <Route element={<AppRoot />}>
            <Route
              path="/loan-applications"
              element={<div data-testid="protected-content">Loan applications</div>}
            />
          </Route>
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

describe("SessionProvider bootstrap recovery", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("blocks cached authorization on a transient context failure and recovers on retry", async () => {
    vi.mocked(refreshSession)
      .mockRejectedValueOnce(
        new SessionRestoreError("CONTEXT_UNAVAILABLE", new TypeError("Failed to fetch")),
      )
      .mockResolvedValueOnce({ status: "authenticated", session: adminSession });

    renderProtectedApp();

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't restore your session");
    expect(screen.queryByTestId("protected-content")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByTestId("protected-content")).toBeInTheDocument();
    expect(refreshSession).toHaveBeenCalledTimes(2);
  });
});
