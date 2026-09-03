import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";

const useUsersMock = vi.fn();
const useLspsMock = vi.fn();
const useCreateUserMock = vi.fn();
const useUpdateUserMock = vi.fn();
const useResetUserPasswordMock = vi.fn();
const useRevokeUserSessionsMock = vi.fn();

const user = {
  id: "user-1",
  username: "operator",
  email: "operator@example.com",
  role: "SYSTEM_ADMIN",
  status: "ACTIVE",
  lspId: null,
};

function mutation() {
  return {
    isPending: false,
    isError: false,
    error: null,
    mutate: vi.fn(),
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    reset: vi.fn(),
  };
}

let createMutation: ReturnType<typeof mutation>;
let updateMutation: ReturnType<typeof mutation>;
let resetMutation: ReturnType<typeof mutation>;
let revokeMutation: ReturnType<typeof mutation>;

vi.mock("@/components/app/layout/AdminEntityListPage", () => ({
  AdminEntityListPage: (props: {
    primaryAction: { label: string; onClick: () => void };
    banner?: ReactNode;
    filterBar: ReactNode;
    table: ReactNode;
    dialogs: ReactNode;
  }) => (
    <main>
      <button onClick={props.primaryAction.onClick}>{props.primaryAction.label}</button>
      {props.banner}
      {props.filterBar}
      {props.table}
      {props.dialogs}
    </main>
  ),
}));
vi.mock("./hooks/useUsers", () => ({ useUsers: (...args: unknown[]) => useUsersMock(...args) }));
vi.mock("@/features/lsps/hooks/useLsps", () => ({
  useLsps: (...args: unknown[]) => useLspsMock(...args),
}));
vi.mock("./hooks/useCreateUser", () => ({
  useCreateUser: () => useCreateUserMock(),
}));
vi.mock("./hooks/useUpdateUser", () => ({
  useUpdateUser: () => useUpdateUserMock(),
}));
vi.mock("./hooks/useResetUserPassword", () => ({
  useResetUserPassword: () => useResetUserPasswordMock(),
}));
vi.mock("./hooks/useRevokeUserSessions", () => ({
  useRevokeUserSessions: () => useRevokeUserSessionsMock(),
}));
vi.mock("./components/UsersFilterBar", () => ({
  UsersFilterBar: (props: { onChange: (filters: { q: string; page: number }) => void }) => (
    <button onClick={() => props.onChange({ q: "updated", page: 0 })}>Change filters</button>
  ),
}));
vi.mock("./components/UsersTable", () => ({
  UsersTable: (props: {
    onEdit: (row: typeof user) => void;
    onResetPassword: (row: typeof user) => void;
    onRevokeSessions: (row: typeof user) => void;
    onToggleStatus: (row: typeof user) => void;
  }) => (
    <div>
      <button onClick={() => props.onEdit(user)}>Edit row</button>
      <button onClick={() => props.onResetPassword(user)}>Reset row</button>
      <button onClick={() => props.onRevokeSessions(user)}>Revoke row</button>
      <button onClick={() => props.onToggleStatus(user)}>Toggle row</button>
      {/* Disabling is confirmed, re-enabling is immediate, so the two paths
          need separate triggers. */}
      <button onClick={() => props.onToggleStatus({ ...user, status: "DISABLED" })}>
        Toggle disabled row
      </button>
    </div>
  ),
}));
vi.mock("./components/UserCreateDialog", () => ({
  UserCreateDialog: (props: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onConfirm: (input: {
      username: string;
      email: string;
      role: "SYSTEM_ADMIN";
      lspId: null;
      idempotencyKey: string;
    }) => void;
    onAcknowledgePassword: () => void;
  }) => (
    <div data-testid="create-dialog" data-open={String(props.open)}>
      <button onClick={() => props.onOpenChange(false)}>Close create</button>
      <button
        onClick={() =>
          props.onConfirm({
            username: "new-user",
            email: "new@example.com",
            role: "SYSTEM_ADMIN",
            lspId: null,
            idempotencyKey: "create-key",
          })
        }
      >
        Confirm create
      </button>
      <button onClick={props.onAcknowledgePassword}>Acknowledge create</button>
    </div>
  ),
}));
vi.mock("./components/UserEditDialog", () => ({
  UserEditDialog: (props: {
    open: boolean;
    user: typeof user | null;
    onConfirm: (input: {
      email: string;
      role: "SYSTEM_ADMIN";
      lspId: null;
      status: "ACTIVE";
      idempotencyKey: string;
    }) => void;
  }) => (
    <div data-testid="edit-dialog" data-open={String(props.open)}>
      {props.user?.username}
      <button
        onClick={() =>
          props.onConfirm({
            email: "edited@example.com",
            role: "SYSTEM_ADMIN",
            lspId: null,
            status: "ACTIVE",
            idempotencyKey: "edit-key",
          })
        }
      >
        Confirm edit
      </button>
    </div>
  ),
}));
vi.mock("./components/ResetPasswordDialog", () => ({
  ResetPasswordDialog: (props: {
    open: boolean;
    username: string;
    onConfirm: (input: { idempotencyKey: string }) => void;
    onAcknowledgePassword: () => void;
  }) => (
    <div data-testid="reset-dialog" data-open={String(props.open)}>
      {props.username}
      <button onClick={() => props.onConfirm({ idempotencyKey: "reset-key" })}>
        Confirm reset
      </button>
      <button onClick={props.onAcknowledgePassword}>Acknowledge reset</button>
    </div>
  ),
}));
vi.mock("./components/RevokeSessionsDialog", () => ({
  RevokeSessionsDialog: (props: {
    open: boolean;
    username: string;
    onConfirm: (input: { reason: string; idempotencyKey: string }) => void;
  }) => (
    <div data-testid="revoke-dialog" data-open={String(props.open)}>
      {props.username}
      <button onClick={() => props.onConfirm({ reason: "security", idempotencyKey: "revoke-key" })}>
        Confirm revoke
      </button>
    </div>
  ),
}));
vi.mock("./components/TempPasswordRevealCard", () => ({
  TempPasswordRevealCard: () => <div>temporary password</div>,
}));

import { UsersPage } from "./page";

beforeEach(() => {
  useUsersMock.mockReset().mockReturnValue({
    data: { items: [user], total: 1, page: 0, pageSize: 25 },
    isPending: false,
  });
  useLspsMock.mockReset().mockReturnValue({ data: { items: [] } });
  createMutation = mutation();
  updateMutation = mutation();
  resetMutation = mutation();
  revokeMutation = mutation();
  useCreateUserMock.mockReset().mockReturnValue(createMutation);
  useUpdateUserMock.mockReset().mockReturnValue(updateMutation);
  useResetUserPasswordMock.mockReset().mockReturnValue(resetMutation);
  useRevokeUserSessionsMock.mockReset().mockReturnValue(revokeMutation);
});

describe("UsersPage dialog coordination", () => {
  it("allows exactly one workflow dialog at a time", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New user" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Reset row" }));
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("reset-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Revoke row" }));
    expect(screen.getByTestId("reset-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("revoke-dialog")).toHaveAttribute("data-open", "true");
  });

  it("hydrates URL filters and writes filter changes back to the URL", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter initialEntries={["/users?q=demo&page=2&pageSize=50&status=ACTIVE"]}>
        <UsersPage />
      </MemoryRouter>,
    );

    expect(useUsersMock).toHaveBeenCalledWith(
      expect.objectContaining({ q: "demo", page: 2, pageSize: 50, status: "ACTIVE" }),
    );
    await operator.click(screen.getByRole("button", { name: "Change filters" }));
    expect(useUsersMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: "updated" }));
    expect(useUsersMock.mock.lastCall?.[0]).not.toHaveProperty("page");
  });

  it("routes each user action to the matching mutation and target", async () => {
    const operator = userEvent.setup();
    createMutation.mutateAsync.mockResolvedValue({
      user: { ...user, username: "new-user" },
      temporaryPassword: "create-secret",
    });
    resetMutation.mutateAsync.mockResolvedValue({ temporaryPassword: "reset-secret" });
    renderWithProviders(
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New user" }));
    await operator.click(screen.getByRole("button", { name: "Confirm create" }));
    expect(createMutation.mutateAsync).toHaveBeenCalledWith({
      username: "new-user",
      email: "new@example.com",
      role: "SYSTEM_ADMIN",
      lspId: null,
      idempotencyKey: "create-key",
    });
    expect(await screen.findByText("temporary password")).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Acknowledge create" }));

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm edit" }));
    expect(updateMutation.mutateAsync).toHaveBeenCalledWith({
      id: "user-1",
      email: "edited@example.com",
      role: "SYSTEM_ADMIN",
      lspId: null,
      status: "ACTIVE",
      idempotencyKey: "edit-key",
    });

    await operator.click(screen.getByRole("button", { name: "Reset row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm reset" }));
    expect(resetMutation.mutateAsync).toHaveBeenCalledWith({
      id: "user-1",
      idempotencyKey: "reset-key",
    });
    await operator.click(screen.getByRole("button", { name: "Acknowledge reset" }));

    await operator.click(screen.getByRole("button", { name: "Revoke row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm revoke" }));
    expect(revokeMutation.mutateAsync).toHaveBeenCalledWith({
      id: "user-1",
      username: "operator",
      reason: "security",
      idempotencyKey: "revoke-key",
    });

    // Disabling locks a person out, so it is confirmed rather than applied on
    // the first click — see `handleToggleStatus`.
    await operator.click(screen.getByRole("button", { name: "Toggle row" }));
    expect(updateMutation.mutate).not.toHaveBeenCalled();

    expect(await screen.findByRole("heading", { name: "Disable user" })).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Disable user" }));
    expect(updateMutation.mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ id: "user-1", status: "DISABLED" }),
    );
  });

  it("re-enables a disabled user without a confirmation step", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>,
    );

    await operator.click(await screen.findByRole("button", { name: "Toggle disabled row" }));
    expect(updateMutation.mutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: "user-1", status: "ACTIVE" }),
    );
    expect(screen.queryByRole("heading", { name: "Disable user" })).not.toBeInTheDocument();
  });

  it("keeps a dialog open when its mutation fails", async () => {
    const operator = userEvent.setup();
    updateMutation.mutateAsync.mockRejectedValue(new Error("update failed"));
    renderWithProviders(
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm edit" }));
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "true");
  });
});
