import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";

const useLspsMock = vi.fn();
const useCreateLspMock = vi.fn();
const useUpdateLspStatusMock = vi.fn();
const useLspAuditEventsMock = vi.fn();

const lsp = {
  id: "lsp-1",
  code: "ACME",
  name: "Acme Finance",
  status: "ACTIVE",
  userCount: 2,
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
let statusMutation: ReturnType<typeof mutation>;

vi.mock("@/components/app/layout/AdminEntityListPage", () => ({
  AdminEntityListPage: (props: {
    primaryAction: { label: string; onClick: () => void };
    filterBar: ReactNode;
    table: ReactNode;
    dialogs: ReactNode;
  }) => (
    <main>
      <button onClick={props.primaryAction.onClick}>{props.primaryAction.label}</button>
      {props.filterBar}
      {props.table}
      {props.dialogs}
    </main>
  ),
}));
vi.mock("./hooks/useLsps", () => ({ useLsps: (...args: unknown[]) => useLspsMock(...args) }));
vi.mock("./hooks/useCreateLsp", () => ({ useCreateLsp: () => useCreateLspMock() }));
vi.mock("./hooks/useUpdateLspStatus", () => ({
  useUpdateLspStatus: () => useUpdateLspStatusMock(),
}));
vi.mock("./hooks/useLspAuditEvents", () => ({
  useLspAuditEvents: (...args: unknown[]) => useLspAuditEventsMock(...args),
}));
vi.mock("./components/LspsFilterBar", () => ({
  LspsFilterBar: (props: { onChange: (filters: { q: string; page: number }) => void }) => (
    <button onClick={() => props.onChange({ q: "updated", page: 0 })}>Change filters</button>
  ),
}));
vi.mock("./components/LspsTable", () => ({
  LspsTable: (props: {
    onDetails: (row: typeof lsp) => void;
    onChangeStatus: (row: typeof lsp) => void;
    onViewAudit: (row: typeof lsp) => void;
  }) => (
    <div>
      <button onClick={() => props.onDetails(lsp)}>Details row</button>
      <button onClick={() => props.onChangeStatus(lsp)}>Status row</button>
      <button onClick={() => props.onViewAudit(lsp)}>Audit row</button>
    </div>
  ),
}));
vi.mock("./components/LspCreateDialog", () => ({
  LspCreateDialog: (props: {
    open: boolean;
    onConfirm: (input: { code: string; name: string; idempotencyKey: string }) => void;
  }) => (
    <div data-testid="create-dialog" data-open={String(props.open)}>
      <button
        onClick={() =>
          props.onConfirm({ code: "NEWLSP", name: "New LSP", idempotencyKey: "create-key" })
        }
      >
        Confirm create
      </button>
    </div>
  ),
}));
vi.mock("./components/LspDetailsDialog", () => ({
  LspDetailsDialog: (props: {
    open: boolean;
    onChangeStatus: () => void;
    onViewAudit: () => void;
    onManageIpAllowlists: () => void;
  }) => (
    <div data-testid="details-dialog" data-open={String(props.open)}>
      <button onClick={props.onChangeStatus}>Details status</button>
      <button onClick={props.onViewAudit}>Details audit</button>
      <button onClick={props.onManageIpAllowlists}>Details allowlist</button>
    </div>
  ),
}));
vi.mock("./components/LspIpAllowlistDialog", () => ({
  LspIpAllowlistDialog: (props: { open: boolean }) => (
    <div data-testid="allowlist-dialog" data-open={String(props.open)} />
  ),
}));
vi.mock("./components/LspStatusChangeDialog", () => ({
  LspStatusChangeDialog: (props: {
    open: boolean;
    onConfirm: (input: {
      id: string;
      status: "INACTIVE";
      reason: "OPERATIONAL";
      note: string;
      idempotencyKey: string;
    }) => void;
  }) => (
    <div data-testid="status-dialog" data-open={String(props.open)}>
      <button
        onClick={() =>
          props.onConfirm({
            id: "lsp-1",
            status: "INACTIVE",
            reason: "OPERATIONAL",
            note: "risk",
            idempotencyKey: "status-key",
          })
        }
      >
        Confirm status
      </button>
    </div>
  ),
}));
vi.mock("./components/LspAuditEventsDialog", () => ({
  LspAuditEventsDialog: (props: { open: boolean }) => (
    <div data-testid="audit-dialog" data-open={String(props.open)} />
  ),
}));

import { LspsPage } from "./page";

beforeEach(() => {
  useLspsMock.mockReset().mockReturnValue({
    data: { items: [lsp], total: 1, page: 0, pageSize: 25 },
    isPending: false,
    isFetching: false,
  });
  createMutation = mutation();
  statusMutation = mutation();
  useCreateLspMock.mockReset().mockReturnValue(createMutation);
  useUpdateLspStatusMock.mockReset().mockReturnValue(statusMutation);
  useLspAuditEventsMock.mockReset().mockReturnValue({
    data: [],
    isPending: false,
    isError: false,
    refetch: vi.fn(),
  });
});

describe("LspsPage dialog coordination", () => {
  it("transitions between mutually exclusive LSP workflows", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter>
        <LspsPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New LSP" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Details row" }));
    expect(screen.getByTestId("create-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("details-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Details allowlist" }));
    expect(screen.getByTestId("details-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("allowlist-dialog")).toHaveAttribute("data-open", "true");

    await operator.click(screen.getByRole("button", { name: "Audit row" }));
    expect(screen.getByTestId("allowlist-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("audit-dialog")).toHaveAttribute("data-open", "true");
    expect(useLspAuditEventsMock).toHaveBeenLastCalledWith("lsp-1", true);
  });

  it("hydrates URL filters and writes filter changes", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter initialEntries={["/lsps?q=demo&page=2&pageSize=50&status=ACTIVE"]}>
        <LspsPage />
      </MemoryRouter>,
    );

    expect(useLspsMock).toHaveBeenCalledWith(
      expect.objectContaining({ q: "demo", page: 2, pageSize: 50, status: "ACTIVE" }),
    );
    await operator.click(screen.getByRole("button", { name: "Change filters" }));
    expect(useLspsMock).toHaveBeenLastCalledWith(expect.objectContaining({ q: "updated" }));
    expect(useLspsMock.mock.lastCall?.[0]).not.toHaveProperty("page");
  });

  it("routes create and status confirmations to their mutations", async () => {
    const operator = userEvent.setup();
    renderWithProviders(
      <MemoryRouter>
        <LspsPage />
      </MemoryRouter>,
    );

    await operator.click(screen.getByRole("button", { name: "New LSP" }));
    await operator.click(screen.getByRole("button", { name: "Confirm create" }));
    expect(createMutation.mutateAsync).toHaveBeenCalledWith({
      code: "NEWLSP",
      name: "New LSP",
      idempotencyKey: "create-key",
    });

    await operator.click(screen.getByRole("button", { name: "Status row" }));
    await operator.click(screen.getByRole("button", { name: "Confirm status" }));
    expect(statusMutation.mutateAsync).toHaveBeenCalledWith({
      id: "lsp-1",
      status: "INACTIVE",
      reason: "OPERATIONAL",
      note: "risk",
      idempotencyKey: "status-key",
    });
    expect(screen.getByTestId("status-dialog")).toHaveAttribute("data-open", "false");
    expect(screen.getByTestId("audit-dialog")).toHaveAttribute("data-open", "true");
  });
});
