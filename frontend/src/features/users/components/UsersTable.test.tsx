import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { DensityProvider } from "@/app/providers";
import { TooltipProvider } from "@/components/ui/tooltip";
import { renderWithProviders } from "@/test/utils";
import type { UsersListResponse } from "../types";
import { UsersTable } from "./UsersTable";

// UsersTable reads the session for the self-disable guard (audit F18);
// these tests don't exercise it, so a null session suffices.
vi.mock("@/features/auth/session-context", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/features/auth/session-context")>();
  return {
    ...actual,
    useSession: () => ({
      session: null,
      isLoading: false,
      signIn: vi.fn(),
      signOut: vi.fn(),
    }),
  };
});

const baseRow = {
  id: "11111111-1111-1111-1111-111111111111",
  username: "locked.user",
  email: "locked.user@bhawana.local",
  status: "ACTIVE" as const,
  role: "OPS_USER" as const,
  lspId: null,
  lspName: null,
  mustChangePassword: false,
  createdAt: "2026-06-08T10:00:00.000Z",
  lockedAt: "2026-06-08T10:00:00.000Z",
  lockReason: "BRUTE_FORCE",
};

function renderTable(data: UsersListResponse) {
  return renderWithProviders(
    <TooltipProvider>
      <DensityProvider>
        <UsersTable
          data={data}
          isLoading={false}
          filters={{ page: 0, pageSize: 25 }}
          onFiltersChange={vi.fn()}
          onEdit={vi.fn()}
          onResetPassword={vi.fn()}
          onRevokeSessions={vi.fn()}
          onToggleStatus={vi.fn()}
        />
      </DensityProvider>
    </TooltipProvider>,
  );
}

describe("UsersTable lockout badge", () => {
  it("shows a Locked badge when lockedAt is present", () => {
    renderTable({
      items: [baseRow],
      total: 1,
      page: 0,
      pageSize: 25,
    });

    expect(screen.getByTestId("users-locked-badge")).toBeInTheDocument();
    expect(screen.getByText("Locked")).toBeInTheDocument();
  });

  it("does not show a Locked badge for unlocked users", () => {
    renderTable({
      items: [{ ...baseRow, lockedAt: null, lockReason: null }],
      total: 1,
      page: 0,
      pageSize: 25,
    });

    expect(screen.queryByTestId("users-locked-badge")).not.toBeInTheDocument();
  });
});
