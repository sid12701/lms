/**
 * `AuditTable` had no test file at all, which is how it kept a
 * `nested-interactive` violation on every one of its rows: the row carried
 * `role="button"` while containing a subject link and a copy button.
 * `DataTable` guards the same invariant (its interactive rows keep the implicit
 * `row` role), but that assertion could never reach this component.
 */
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AuditTable } from "./AuditTable";
import type { AuditEventsResponse, AuditRow } from "../types";

function makeRow(overrides: Partial<AuditRow> = {}): AuditRow {
  return {
    id: "DOCUMENT_ACCESS:26fb996f-9363-4d87-819b-da0e4e42cead",
    stream: "ACCESS",
    createdAt: "2026-08-10T09:58:00.000Z",
    actorId: "user-1",
    actorName: "ops.admin",
    actorRole: "SYSTEM_ADMIN",
    correlationId: "3f0f0f2a-1f3e-4a1a-9c1a-2b3c4d5e6f70",
    subjectType: "LOAN_APPLICATION",
    subjectId: "254fd57c-fe03-4507-a88b-bb3e73d4df64",
    headline: "Document accessed",
    ...overrides,
  } as AuditRow;
}

function makeData(rows: AuditRow[]): AuditEventsResponse {
  return { items: rows, total: rows.length, page: 0, pageSize: 25 };
}

describe("AuditTable", () => {
  it("keeps the implicit row role so in-row controls are not nested interactives", () => {
    const { container } = renderWithProviders(
      <AuditTable data={makeData([makeRow()])} isLoading={false} onSelect={vi.fn()} />,
    );

    const row = container.querySelector<HTMLTableRowElement>("tbody tr");
    expect(row).toBeTruthy();
    // A `role="button"` here would both nest the subject link and the copy
    // button inside an interactive ancestor and strip the row of its table
    // semantics.
    expect(row!.getAttribute("role")).toBeNull();
    expect(row!.tabIndex).toBe(0);
    // The controls that made the nesting a violation are genuinely present.
    expect(row!.querySelector("a[href]")).toBeTruthy();
    expect(row!.querySelector("button")).toBeTruthy();
  });

  it("names the row by its humanised headline rather than the raw event id", () => {
    const { container } = renderWithProviders(
      <AuditTable
        data={makeData([makeRow({ headline: "PAN revealed" })])}
        isLoading={false}
        onSelect={vi.fn()}
      />,
    );

    const row = container.querySelector<HTMLTableRowElement>("tbody tr");
    expect(row!.getAttribute("aria-label")).toBe("Open audit event PAN revealed");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <AuditTable
        data={makeData([makeRow(), makeRow({ id: "AUTH:second", headline: "Signed in" })])}
        isLoading={false}
        onSelect={vi.fn()}
      />,
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
