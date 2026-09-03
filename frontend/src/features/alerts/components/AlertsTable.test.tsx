import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import { AlertsTable } from "./AlertsTable";
import type { AlertRow, AlertsListResponse } from "../types";

const ROW: AlertRow = {
  id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  type: "DELINQUENCY_BUCKET",
  severity: "HIGH",
  status: "OPEN",
  title: "Delinquency bucket DPD_1_30",
  message: "Loan moved into an overdue bucket.",
  subjectType: "LOAN_ACCOUNT",
  subjectId: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  correlationId: "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
  createdAt: "2026-08-03T10:00:00.000Z",
  acknowledgedAt: null,
  acknowledgedBy: null,
  acknowledgmentNote: null,
  acknowledgedByName: null,
};

const DATA: AlertsListResponse = {
  items: [ROW],
  total: 1,
  page: 0,
  pageSize: 25,
};

function renderTable() {
  return renderWithProviders(
    <MemoryRouter>
      <DensityProvider>
        <AlertsTable
          data={DATA}
          isLoading={false}
          filters={{}}
          onFiltersChange={vi.fn()}
          onAcknowledge={vi.fn()}
        />
      </DensityProvider>
    </MemoryRouter>,
  );
}

describe("AlertsTable", () => {
  it("renders alert copy through the shared display helpers", () => {
    renderTable();

    // The table must not re-implement these locally. Regressing any of the
    // three back to raw fields is what this asserts against:
    //   title    -> humanizeAlertTitle
    //   subject  -> alertSubjectTypeLabel (not an ad-hoc `replace().toLowerCase()`)
    //   created  -> AbsoluteRelativeTime (not a native `title=` tooltip)
    expect(screen.getByText("Delinquency · 1–30 DPD")).toBeInTheDocument();
    expect(screen.queryByText("Delinquency bucket DPD_1_30")).toBeNull();

    expect(screen.getByText("Loan account")).toBeInTheDocument();
    expect(screen.queryByText("LOAN_ACCOUNT")).toBeNull();

    expect(document.querySelector('[data-slot="absolute-relative-time"]')).not.toBeNull();
  });
});
