import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { BlockingIssuesPanel } from "./BlockingIssuesPanel";
import type { LoanApplicationDetail } from "../../types";

function makeDetail(status: LoanApplicationDetail["application"]["status"]): LoanApplicationDetail {
  return {
    application: {
      id: "app-1",
      externalLoanId: "EXT-9",
      status,
    },
    borrower: { fullName: "Aanya Devi" },
    account: null,
    docsComplete: true,
    scheduleValid: true,
  } as unknown as LoanApplicationDetail;
}

describe("BlockingIssuesPanel", () => {
  // L02 — the retry card pointed at a "Disbursements" tab that does not
  // exist; the adapter response actually lives on the Activity timeline.
  it("links the disbursement-retry card to the Activity tab", () => {
    const { getByRole, queryByText } = renderWithProviders(
      <MemoryRouter initialEntries={["/loan-applications/app-1"]}>
        <BlockingIssuesPanel detail={makeDetail("DISBURSEMENT_RETRY")} />
      </MemoryRouter>,
    );

    const link = getByRole("link", { name: "Activity tab" });
    expect(link.getAttribute("href")).toContain("tab=activity");
    expect(queryByText(/Disbursements tab/)).toBeNull();
  });

  it("shows the awaiting-disbursement card while queued", () => {
    const { getByText } = renderWithProviders(
      <MemoryRouter>
        <BlockingIssuesPanel detail={makeDetail("APPROVED_PENDING_DISBURSAL")} />
      </MemoryRouter>,
    );
    expect(getByText("Awaiting disbursement")).not.toBeNull();
  });

  it("shows the disbursed confirmation in a terminal state", () => {
    const { getByText } = renderWithProviders(
      <MemoryRouter>
        <BlockingIssuesPanel detail={makeDetail("DISBURSED")} />
      </MemoryRouter>,
    );
    expect(getByText("Loan disbursed")).not.toBeNull();
  });

  it("renders nothing for statuses without a blocking issue", () => {
    const { container } = renderWithProviders(
      <MemoryRouter>
        <BlockingIssuesPanel detail={makeDetail("AWAITING_APPROVAL")} />
      </MemoryRouter>,
    );
    expect(container.querySelector('[data-slot="blocking-issues-panel"]')).toBeNull();
  });
});
