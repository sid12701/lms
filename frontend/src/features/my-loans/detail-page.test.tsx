import { describe, expect, it, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { SessionProvider } from "@/features/auth/session-context";
import type { Session } from "@/features/auth/session-types";
import { MyLoanDetailPage } from "./detail-page";
import { makeMyLoanDetail } from "./test-utils";
import { renderWithProviders } from "@/test/utils";

const fetchMyLoanDetailMock = vi.fn();
const fetchInvalidReasonsMock = vi.fn();
const listLspSubmittedDocumentsMock = vi.fn();
const uploadLspDocumentMock = vi.fn();
const fetchLspDocumentRequirementsMock = vi.fn();

vi.mock("./api", () => ({
  fetchMyLoanDetail: (...args: unknown[]) => fetchMyLoanDetailMock(...args),
  fetchInvalidReasons: (...args: unknown[]) => fetchInvalidReasonsMock(...args),
  listLspSubmittedDocuments: (...args: unknown[]) => listLspSubmittedDocumentsMock(...args),
  uploadLspDocument: (...args: unknown[]) => uploadLspDocumentMock(...args),
  fetchLspDocumentRequirements: (...args: unknown[]) => fetchLspDocumentRequirementsMock(...args),
  fetchMyLoanRepaymentSchedule: vi.fn().mockResolvedValue([]),
  fetchMyLoanPayments: vi.fn().mockResolvedValue([]),
}));

function sessionFor(role: Session["user"]["role"]): Session {
  return {
    user: {
      id: "aaaaaaaa-1111-4aaa-8aaa-aaaaaaaaaaaa",
      username: role.toLowerCase(),
      role,
      lspId: "bbbbbbbb-2222-4bbb-8bbb-bbbbbbbbbbbb",
      mustChangePassword: false,
    },
    accessToken: "test.token",
    expiresAt: "2030-01-01T00:00:00.000Z",
  };
}

const detail = makeMyLoanDetail();

function renderPage(role: Session["user"]["role"]) {
  return renderWithProviders(
    <MemoryRouter initialEntries={[`/my-loans/${detail.id}`]}>
      <SessionProvider skipBootstrap initialSession={sessionFor(role)}>
        <Routes>
          <Route path="/my-loans/:id" element={<MyLoanDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  fetchMyLoanDetailMock.mockReset().mockResolvedValue(detail);
  fetchInvalidReasonsMock.mockReset().mockResolvedValue([]);
  fetchLspDocumentRequirementsMock.mockReset().mockResolvedValue([
    {
      code: "PAN_CARD",
      displayName: "PAN",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "AADHAAR_FILE",
      displayName: "Aadhaar",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "ADDRESS_PROOF",
      displayName: "Address proof",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "INCOME_PROOF",
      displayName: "Income proof",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "BANK_STATEMENT",
      displayName: "Bank statement",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "SELFIE_PHOTOGRAPH",
      displayName: "Photograph",
      requiredForApproval: true,
      requiredForDisbursement: true,
    },
    {
      code: "KFS",
      displayName: "Key Facts Statement",
      requiredForApproval: false,
      requiredForDisbursement: true,
    },
    {
      code: "LOAN_AGREEMENT",
      displayName: "Loan agreement",
      requiredForApproval: false,
      requiredForDisbursement: true,
    },
  ]);
  listLspSubmittedDocumentsMock.mockReset().mockResolvedValue([
    {
      documentType: "BANK_STATEMENT",
      status: "SUBMITTED",
      fileName: "bank_statement.pdf",
      contentType: "application/pdf",
      note: null,
      uploadedAt: "2026-06-21T13:15:00.000Z",
      uploadedByUsername: "lsp.writer",
    },
  ]);
  uploadLspDocumentMock.mockReset();
});

describe("MyLoanDetailPage role actions", () => {
  it("does not show mutation controls to LSP read-only users", async () => {
    // The "read-only for your access level" copy only renders for a
    // non-terminal loan (DocumentsSection swaps in different wording once a
    // loan is terminal) — this test's assertion depends on that, so the
    // precondition is stated rather than borrowed from the fixture default.
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "UNDER_REPAYMENT", rawStatus: "UNDER_REPAYMENT" }),
    );
    renderPage("LSP_UI_READ");

    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /mark invalid/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^upload$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^replace$/i })).not.toBeInTheDocument();
    expect(screen.getByText("Documents are read-only for your access level.")).toBeInTheDocument();
  });

  it("shows mutation controls to LSP write users", async () => {
    // Mark invalid / upload / replace are all gated on the loan being
    // non-terminal (`canMutateLoan = canWriteLoan && !isTerminal`); stating
    // the status explicitly documents why this loan qualifies.
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "UNDER_REPAYMENT", rawStatus: "UNDER_REPAYMENT" }),
    );
    renderPage("LSP_UI_WRITE");

    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /mark invalid/i })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^upload$/i }).length).toBeGreaterThan(0);
    // "Replace" only appears once `useLspSubmittedDocuments` resolves and the
    // already-submitted bank statement row flips out of its default "Upload"
    // state — a separate query from the one the heading assertion above
    // waits on, so this needs its own wait rather than a bare `getByRole`.
    expect(await screen.findByRole("button", { name: /^replace$/i })).toBeInTheDocument();
  });

  it("renders a stable loading state while the detail request is pending", () => {
    fetchMyLoanDetailMock.mockReturnValue(new Promise(() => undefined));
    renderPage("LSP_UI_READ");

    expect(screen.getByRole("status", { name: "Loading loan details" })).toBeInTheDocument();
    expect(document.querySelector('[data-slot="my-loan-detail-skeleton"]')).not.toBeNull();
  });

  it("retries a failed detail query without reloading the browser", async () => {
    const operator = userEvent.setup();
    fetchMyLoanDetailMock
      .mockRejectedValueOnce(new Error("service unavailable"))
      .mockResolvedValueOnce(detail);
    renderPage("LSP_UI_READ");

    expect(await screen.findByText("Couldn't load this loan")).toBeInTheDocument();
    await operator.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("heading", { name: "Aarav Singh" })).toBeInTheDocument();
    expect(fetchMyLoanDetailMock).toHaveBeenCalledTimes(2);
  });

  it("distinguishes a missing loan from a transient load failure", async () => {
    fetchMyLoanDetailMock.mockRejectedValue(new Error("Loan not found"));
    renderPage("LSP_UI_READ");

    expect(await screen.findByText("Loan not found")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry" })).not.toBeInTheDocument();
  });

  it("loads invalid reasons only when the write workflow opens", async () => {
    const operator = userEvent.setup();
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "AWAITING_APPROVAL", rawStatus: "AWAITING_APPROVAL" }),
    );
    fetchInvalidReasonsMock.mockResolvedValue([
      { code: "DUPLICATE", label: "Duplicate application", requiresText: false },
    ]);
    renderPage("LSP_UI_WRITE");

    await screen.findByRole("heading", { name: "Aarav Singh" });
    expect(fetchInvalidReasonsMock).not.toHaveBeenCalled();
    await operator.click(screen.getByRole("button", { name: /mark invalid/i }));

    expect(await screen.findByText("Mark loan invalid")).toBeInTheDocument();
    expect(fetchInvalidReasonsMock).toHaveBeenCalledTimes(1);
  });

  it("keeps the invalidation dialog usable when reason loading fails", async () => {
    const operator = userEvent.setup();
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "AWAITING_APPROVAL", rawStatus: "AWAITING_APPROVAL" }),
    );
    fetchInvalidReasonsMock.mockRejectedValue(new Error("reason catalogue unavailable"));
    renderPage("LSP_UI_WRITE");

    await screen.findByRole("heading", { name: "Aarav Singh" });
    await operator.click(screen.getByRole("button", { name: /mark invalid/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("reason catalogue unavailable");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
  });

  /**
   * The money state governs what the interface offers. `UNDER_REPAYMENT` has no
   * rule to `INVALID` (see `PRE_DISBURSAL_INVALIDATABLE`), so the action must be
   * unavailable *and* say why — rather than being offered and then rejected by
   * the backend after the operator has picked a reason and submitted.
   */
  it.each([["UNDER_REPAYMENT" as const], ["DISBURSED" as const]])(
    "disables invalidation for %s loans and says why",
    async (status) => {
      fetchMyLoanDetailMock.mockResolvedValue(makeMyLoanDetail({ status, rawStatus: status }));
      renderPage("LSP_UI_WRITE");

      await screen.findByRole("heading", { name: "Aarav Singh" });
      const button = screen.getByRole("button", { name: /mark invalid/i });
      expect(button).toHaveAttribute("aria-disabled", "true");

      // The reason must be reachable by assistive tech, not only by hovering a
      // disabled (and therefore unfocusable) button.
      const reasonId = button.getAttribute("aria-describedby");
      expect(reasonId).toBeTruthy();
      expect(document.getElementById(reasonId as string)).toHaveTextContent(/entered servicing/i);
      expect(fetchInvalidReasonsMock).not.toHaveBeenCalled();
    },
  );

  it("hides invalidation entirely once the loan reaches a final status", async () => {
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "CLOSED", rawStatus: "CLOSED" }),
    );
    renderPage("LSP_UI_WRITE");

    await screen.findByRole("heading", { name: "Aarav Singh" });
    expect(screen.queryByRole("button", { name: /mark invalid/i })).not.toBeInTheDocument();
  });

  it("still offers invalidation before disbursement", async () => {
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({ status: "AWAITING_APPROVAL", rawStatus: "AWAITING_APPROVAL" }),
    );
    renderPage("LSP_UI_WRITE");

    await screen.findByRole("heading", { name: "Aarav Singh" });
    expect(screen.getByRole("button", { name: /mark invalid/i })).toBeEnabled();
  });

  it("humanises recent activity type and summary", async () => {
    fetchMyLoanDetailMock.mockResolvedValue(
      makeMyLoanDetail({
        lastActivity: {
          activityType: "STATUS_TRANSITION",
          actorUsername: "ops.user",
          summary: "Moved from INITIALIZED to UNDER_REPAYMENT",
          detail: null,
          correlationId: "corr-1",
          occurredAt: "2026-06-21T13:15:00.000Z",
        },
      }),
    );
    renderPage("LSP_UI_READ");

    expect(await screen.findByText("Status transition")).toBeInTheDocument();
    expect(screen.getByText("Initialized → Under repayment")).toBeInTheDocument();
    expect(screen.queryByText("STATUS_TRANSITION")).toBeNull();
    expect(screen.queryByText("Moved from INITIALIZED to UNDER_REPAYMENT")).toBeNull();
  });
});
