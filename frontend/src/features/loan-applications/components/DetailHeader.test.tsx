/**
 * Host integration tests for `DetailHeader`: role/source affordance,
 * entered reason + key passed exactly once, success result, original error
 * visibility, and no silent manual fallback.
 *
 * Real `DetailHeader` + `ActionBar` + dialogs + mutation hooks run against a
 * mocked `requestJson` HTTP boundary; only the session is stubbed.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/test/utils";
import type { SessionContextValue } from "@/features/auth/session-context-state";
import type { LoanApplicationDetail } from "../types";
import { DetailHeader } from "./DetailHeader";

const requestJsonMock = vi.hoisted(() => vi.fn());
const useSessionMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return { ...actual, requestJson: requestJsonMock };
});

vi.mock("@/features/auth/session-context", () => ({
  useSession: useSessionMock,
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

import { ApiError } from "@/lib/api/http-client";

const APP_ID = "11111111-1111-4111-8111-111111111111";

function stubSession(role: "SYSTEM_ADMIN" | "OPS_USER") {
  useSessionMock.mockReturnValue({
    session: { user: { role, roles: [role] } },
  } as unknown as SessionContextValue);
}

function makeDetail(status: LoanApplicationDetail["application"]["status"]): LoanApplicationDetail {
  return {
    application: {
      id: APP_ID,
      externalLoanId: "EXT-1",
      borrowerId: "borrower-1",
      lspId: "lsp-1",
      productId: "product-1",
      requestedAmount: 100_000,
      tenureMonths: 12,
      status,
      sourceChannel: "UI",
      createdAt: "2026-09-01T00:00:00.000Z",
      updatedAt: "2026-09-02T00:00:00.000Z",
      invalidatedAt: null,
      invalidReason: null,
    },
    borrower: { id: "borrower-1", fullName: "Test Borrower" } as LoanApplicationDetail["borrower"],
    lsp: { id: "lsp-1", code: "LSP", name: "Test LSP" } as LoanApplicationDetail["lsp"],
    product: {
      id: "product-1",
      code: "P",
      name: "Test Product",
    } as LoanApplicationDetail["product"],
    account: null,
    docsComplete: true,
    scheduleValid: true,
    accountDelinquency: null,
    interestRate: null,
  } as LoanApplicationDetail;
}

function detailPayload(status: string) {
  return {
    id: APP_ID,
    borrowerId: "borrower-1",
    lspId: "lsp-1",
    productId: "product-1",
    status,
    createdAt: "2026-09-01T00:00:00.000Z",
    updatedAt: "2026-09-03T00:00:00.000Z",
  };
}

function postPaths() {
  return requestJsonMock.mock.calls.map(([p]) => String(p));
}

function findSubmit(ui: ReturnType<typeof renderWithProviders>, name: RegExp): HTMLButtonElement {
  return ui
    .getAllByRole("button", { name })
    .find((b) => (b as HTMLButtonElement).type === "submit")! as HTMLButtonElement;
}

beforeEach(() => {
  requestJsonMock.mockReset();
  useSessionMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("<DetailHeader /> override affordance", () => {
  it("offers ordinary actions and Override to SYSTEM_ADMIN on a recoverable source", async () => {
    stubSession("SYSTEM_ADMIN");
    requestJsonMock.mockRejectedValue(new Error("no network expected"));
    const ui = renderWithProviders(
      <DetailHeader detail={makeDetail("INITIALIZED")} onTransitionSuccess={() => {}} />,
    );
    expect(await ui.findByRole("button", { name: "Submit for approval" })).toBeInTheDocument();
    expect(ui.getByRole("button", { name: "Override status" })).toBeInTheDocument();
  }, 15_000);

  it("shows OPS_USER only the escalate path: no ordinary actions, no Override", async () => {
    stubSession("OPS_USER");
    requestJsonMock.mockRejectedValue(new Error("no network expected"));
    const ui = renderWithProviders(
      <DetailHeader detail={makeDetail("INITIALIZED")} onTransitionSuccess={() => {}} />,
    );
    expect(await ui.findByRole("button", { name: /Escalate to admin/i })).toBeInTheDocument();
    expect(ui.queryByRole("button", { name: "Submit for approval" })).not.toBeInTheDocument();
    expect(ui.queryByRole("button", { name: "Override status" })).not.toBeInTheDocument();
  }, 15_000);

  it("hides Override for a backend-blocked source (CLOSED) while keeping the header", async () => {
    stubSession("SYSTEM_ADMIN");
    requestJsonMock.mockRejectedValue(new Error("no network expected"));
    const ui = renderWithProviders(
      <DetailHeader detail={makeDetail("CLOSED")} onTransitionSuccess={() => {}} />,
    );
    expect(await ui.findByText("Test Borrower")).toBeInTheDocument();
    expect(ui.queryByRole("button", { name: "Override status" })).not.toBeInTheDocument();
  }, 15_000);
});

describe("<DetailHeader /> ordinary transition", () => {
  it("passes the entered reason + key exactly once and reports success", async () => {
    stubSession("SYSTEM_ADMIN");
    requestJsonMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path.endsWith("/status-transitions")) {
        return Promise.resolve(detailPayload("INVALID"));
      }
      throw new Error(`unexpected call ${path} ${String(init?.method)}`);
    });
    const onTransitionSuccess = vi.fn();
    const ui = renderWithProviders(
      <DetailHeader detail={makeDetail("INITIALIZED")} onTransitionSuccess={onTransitionSuccess} />,
    );

    await userEvent.click(await ui.findByRole("button", { name: "Mark invalid" }));
    await userEvent.type(ui.getByLabelText(/Reason/i), "  Duplicate application.  ");
    await userEvent.click(findSubmit(ui, /Mark invalid/i));

    await waitFor(() => expect(onTransitionSuccess).toHaveBeenCalledTimes(1));
    const calls = requestJsonMock.mock.calls.filter(([p]) =>
      String(p).endsWith("/status-transitions"),
    );
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0]![1]?.body))).toMatchObject({
      targetStatus: "INVALID",
      note: "Duplicate application.",
    });
    const key = (calls[0]![2] as { idempotencyKey?: string } | undefined)?.idempotencyKey;
    expect(typeof key).toBe("string");
    expect(key!.length).toBeGreaterThan(0);
    expect(postPaths().some((p) => p.endsWith("/manual-status"))).toBe(false);
  }, 20_000);

  it("shows the original 400 error with no silent manual fallback", async () => {
    stubSession("SYSTEM_ADMIN");
    const original = new ApiError(
      "Illegal transition for status.",
      400,
      "{}",
      "INVALID_TRANSITION",
    );
    requestJsonMock.mockImplementation((path: string) => {
      if (path.endsWith("/status-transitions")) return Promise.reject(original);
      throw new Error(`unexpected call ${path}`);
    });
    const onTransitionSuccess = vi.fn();
    const ui = renderWithProviders(
      <DetailHeader detail={makeDetail("INITIALIZED")} onTransitionSuccess={onTransitionSuccess} />,
    );

    await userEvent.click(await ui.findByRole("button", { name: "Submit for approval" }));
    await userEvent.click(findSubmit(ui, /Submit for approval/i));

    // Original server error stays visible where the admin is looking:
    // inside the dialog and in the action bar's live region.
    const matches = await ui.findAllByText(/Illegal transition for status/i);
    expect(matches.length).toBeGreaterThanOrEqual(1);
    expect(
      ui.baseElement.querySelector('[data-slot="transition-dialog-error"]')?.textContent,
    ).toMatch(/Illegal transition for status/i);
    expect(onTransitionSuccess).not.toHaveBeenCalled();
    expect(postPaths()).toEqual([
      `/api/v1/internal/ops/loan-applications/${APP_ID}/status-transitions`,
    ]);
  }, 20_000);
});

describe("<DetailHeader /> override", () => {
  it("passes the entered reason code + explanation + key exactly once (no standard call)", async () => {
    stubSession("SYSTEM_ADMIN");
    requestJsonMock.mockImplementation((path: string, init?: RequestInit) => {
      if (path.endsWith("/manual-status")) {
        return Promise.resolve(detailPayload("REJECTED"));
      }
      throw new Error(`unexpected call ${path} ${String(init?.method)}`);
    });
    const onTransitionSuccess = vi.fn();
    const ui = renderWithProviders(
      <DetailHeader
        detail={makeDetail("AWAITING_APPROVAL")}
        onTransitionSuccess={onTransitionSuccess}
      />,
    );

    await userEvent.click(await ui.findByRole("button", { name: "Override status" }));
    await userEvent.click(ui.getByLabelText("Target status"));
    await userEvent.click(await ui.findByRole("option", { name: "REJECTED" }));
    await userEvent.click(ui.getByLabelText("Reason code"));
    await userEvent.click(await ui.findByRole("option", { name: /Failed verification/i }));
    await userEvent.type(ui.getByLabelText(/Explanation/i), "  Verified by phone.  ");
    await userEvent.click(findSubmit(ui, /Confirm override/i));

    await waitFor(() => expect(onTransitionSuccess).toHaveBeenCalledTimes(1));
    const calls = requestJsonMock.mock.calls.filter(([p]) => String(p).endsWith("/manual-status"));
    expect(calls).toHaveLength(1);
    expect(JSON.parse(String(calls[0]![1]?.body))).toEqual({
      targetStatus: "REJECTED",
      note: "Verified by phone.",
      reasonCode: "FAILED_VERIFICATION",
    });
    const key = (calls[0]![2] as { idempotencyKey?: string } | undefined)?.idempotencyKey;
    expect(typeof key).toBe("string");
    expect(key!.length).toBeGreaterThan(0);
    expect(postPaths().some((p) => p.endsWith("/status-transitions"))).toBe(false);
  }, 20_000);
});
