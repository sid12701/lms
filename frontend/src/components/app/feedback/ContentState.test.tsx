import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ContentState } from "./ContentState";

describe("ContentState", () => {
  it("renders children when status is ready", () => {
    const { getByText } = renderWithProviders(
      <ContentState status="ready">
        <p>Body</p>
      </ContentState>,
    );
    expect(getByText("Body")).toBeInTheDocument();
  });

  it("renders the default loading skeleton when no `loading` slot is given", () => {
    const { container } = renderWithProviders(
      <ContentState status="loading">
        <p>x</p>
      </ContentState>,
    );
    expect(container.querySelector('[data-slot="table-skeleton"]')).toBeInTheDocument();
  });

  it("renders the supplied empty/error/permission slots", () => {
    const { rerender, getByText, queryByText } = renderWithProviders(
      <ContentState status="empty" empty={<span>EMPTY</span>}>
        <p>x</p>
      </ContentState>,
    );
    expect(getByText("EMPTY")).toBeInTheDocument();

    rerender(
      <ContentState status="error" error={<span>ERR</span>}>
        <p>x</p>
      </ContentState>,
    );
    expect(getByText("ERR")).toBeInTheDocument();
    expect(queryByText("EMPTY")).not.toBeInTheDocument();

    rerender(
      <ContentState status="permission-denied" permissionDenied={<span>PERM</span>}>
        <p>x</p>
      </ContentState>,
    );
    expect(getByText("PERM")).toBeInTheDocument();
  });

  it("has no axe violations on the loading state", async () => {
    const { container } = renderWithProviders(
      <ContentState status="loading">
        <p>x</p>
      </ContentState>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
