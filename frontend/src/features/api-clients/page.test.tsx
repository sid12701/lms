import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";

const useApiClientsMock = vi.fn();
const useLspsMock = vi.fn();
const useCreateApiClientMock = vi.fn();
const useUpdateApiClientMock = vi.fn();
const useRotateApiClientSecretMock = vi.fn();

const client = {
  id: "client-1",
  name: "Collections integration",
  status: "ACTIVE",
  lspId: "lsp-1",
};

function mutation() {
  return {
    isPending: false,
    isError: false,
    error: null,
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    reset: vi.fn(),
  };
}

let createMutation: ReturnType<typeof mutation>;
let updateMutation: ReturnType<typeof mutation>;
let rotateMutation: ReturnType<typeof mutation>;

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
vi.mock("./hooks/useApiClients", () => ({
  useApiClients: (...args: unknown[]) => useApiClientsMock(...args),
}));
vi.mock("@/features/lsps/hooks/useLsps", () => ({
  useLsps: (...args: unknown[]) => useLspsMock(...args),
}));
vi.mock("./hooks/useCreateApiClient", () => ({
  useCreateApiClient: () => useCreateApiClientMock(),
}));
vi.mock("./hooks/useUpdateApiClient", () => ({
  useUpdateApiClient: () => useUpdateApiClientMock(),
}));
vi.mock("./hooks/useRotateApiClientSecret", () => ({
  useRotateApiClientSecret: () => useRotateApiClientSecretMock(),
}));
vi.mock("./components/ApiClientsFilterBar", () => ({
  ApiClientsFilterBar: (props: { onChange: (filters: { q: string; page: number }) => void }) => (
    <button onClick={() => props.onChange({ q: "updated", page: 0 })}>Change filters</button>
  ),
}));
vi.mock("./components/ApiClientsTable", () => ({
  ApiClientsTable: (props: {
    onEdit: (row: typeof client) => void;
    onRotate: (row: typeof client) => void;
  }) => (
    <div>
      <button onClick={() => props.onEdit(client)}>Edit row</button>
      <button onClick={() => props.onRotate(client)}>Rotate row</button>
    </div>
  ),
}));
vi.mock("./components/ApiClientCreateDialog", () => ({
  ApiClientCreateDialog: (props: {
    open: boolean;
    onOpenChange: (open: boolean) => void;
    onCreate: (input: { name: string; lspId: string; idempotencyKey: string }) => void;
  }) => (
    <div data-testid="create-dialog" data-open={String(props.open)}>
      <button
        onClick={() =>
          props.onCreate({ name: "New integration", lspId: "lsp-1", idempotencyKey: "create-key" })
        }
      >
        Confirm create
      </button>
      <button onClick={() => props.onOpenChange(false)}>Close create</button>
    </div>
  ),
}));
vi.mock("./components/ApiClientEditDialog", () => ({
  ApiClientEditDialog: (props: {
    open: boolean;
    client: typeof client | null;
    onSave: (input: { name: string; status: "DISABLED"; idempotencyKey: string }) => void;
  }) => (
    <div data-testid="edit-dialog" data-open={String(props.open)}>
      {props.client?.name}
      <button
        onClick={() =>
          props.onSave({
            name: "Edited integration",
            status: "DISABLED",
            idempotencyKey: "edit-key",
          })
        }
      >
        Confirm edit
      </button>
    </div>
  ),
}));
vi.mock("@/components/app/secrets", () => ({
  ApiSecretReveal: (props: { clientLabel: string }) => (
    <div>secret reveal for {props.clientLabel}</div>
  ),
  RotateSecretDialog: (props: {
    open: boolean;
    clientLabel?: string;
    onConfirm: (input: { reason: string; idempotencyKey: string }) => void;
  }) => (
    <div data-testid="rotate-dialog" data-open={String(props.open)}>
      {props.clientLabel}
      <button
        onClick={() => props.onConfirm({ reason: "scheduled", idempotencyKey: "rotate-key" })}
      >
        Confirm rotate
      </button>
    </div>
  ),
}));

import { ApiClientsPage } from "./page";

beforeEach(() => {
  useApiClientsMock.mockReset().mockReturnValue({
    data: { items: [client], total: 1, page: 0, pageSize: 25 },
    isPending: false,
    isFetching: false,
  });
  useLspsMock.mockReset().mockReturnValue({ data: { items: [] } });
  createMutation = mutation();
  updateMutation = mutation();
  rotateMutation = mutation();
  useCreateApiClientMock.mockReset().mockReturnValue(createMutation);
  useUpdateApiClientMock.mockReset().mockReturnValue(updateMutation);
  useRotateApiClientSecretMock.mockReset().mockReturnValue(rotateMutation);
});

describe("ApiClientsPage dialog coordination", () => {
  it("moves between create, edit, and rotation without overlapping dialogs", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter>
        <ApiClientsPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New API client" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Rotate row" }));
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("rotate-dialog")).toHaveAttribute("data-open", "true");
    expect(screen.getByTestId("rotate-dialog")).toHaveTextContent("Collections integration");
  });

  it("hydrates URL filters and writes filter changes", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter initialEntries={["/api-clients?q=demo&page=2&pageSize=50&status=ACTIVE"]}>
        <ApiClientsPage />
      </MemoryRouter>,
    );

    expect(useApiClientsMock).toHaveBeenCalledWith(
      expect.objectContaining({ q: "demo", page: 2, pageSize: 50, status: "ACTIVE" }),
    );
    await operator.click(screen.getByRole("button", { name: "Change filters" }));
    expect(useApiClientsMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: "updated" }));
    expect(useApiClientsMock.mock.lastCall?.[0]).not.toHaveProperty("page");
  });

  it("routes create, edit, and rotation to their matching mutation", async () => {
    const operator = userEvent.setup();
    createMutation.mutateAsync.mockResolvedValue({
      client: { ...client, name: "New integration" },
      clientSecret: "created-secret",
    });
    rotateMutation.mutateAsync.mockResolvedValue({ clientSecret: "rotated-secret" });
    renderWithProviders(
      <MemoryRouter>
        <ApiClientsPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New API client" }));
    await operator.click(screen.getByRole("button", { name: "Confirm create" }));
    expect(createMutation.mutateAsync).toHaveBeenCalledWith({
      name: "New integration",
      lspId: "lsp-1",
      idempotencyKey: "create-key",
    });
    await operator.click(screen.getByRole("button", { name: "Close create" }));
    expect(await screen.findByText("secret reveal for New integration")).toBeInTheDocument();

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm edit" }));
    expect(updateMutation.mutateAsync).toHaveBeenCalledWith({
      id: "client-1",
      name: "Edited integration",
      status: "DISABLED",
      idempotencyKey: "edit-key",
    });

    await operator.click(screen.getByRole("button", { name: "Rotate row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm rotate" }));
    expect(rotateMutation.mutateAsync).toHaveBeenCalledWith({
      id: "client-1",
      reason: "scheduled",
      idempotencyKey: "rotate-key",
    });
    expect(
      await screen.findByText("secret reveal for Collections integration"),
    ).toBeInTheDocument();
  });

  it("keeps the edit workflow open when updating fails", async () => {
    const operator = userEvent.setup();
    updateMutation.mutateAsync.mockRejectedValue(new Error("update failed"));
    renderWithProviders(
      <MemoryRouter>
        <ApiClientsPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "Edit row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm edit" }));
    expect(screen.getByTestId("edit-dialog")).toHaveAttribute("data-open", "true");
  });
});
