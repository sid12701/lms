import { useEffect } from "react";
import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { BreadcrumbBar } from "./BreadcrumbBar";
import { PageMetaProvider, usePageMeta } from "./page-meta-context";

function MetaSetter() {
  usePageMeta({ breadcrumbLabel: "LSP-9001", documentTitle: "LSP-9001 · Bhawana Capital" });
  return null;
}

describe("page meta integration", () => {
  it("overrides opaque-id breadcrumb labels from page meta", () => {
    render(
      <MemoryRouter initialEntries={["/loan-applications/0123456789abcdef0123456789abcdef"]}>
        <PageMetaProvider>
          <MetaSetter />
          <BreadcrumbBar />
        </PageMetaProvider>
      </MemoryRouter>,
    );

    expect(screen.getByText("LSP-9001")).toHaveAttribute("aria-current", "page");
    expect(screen.queryByText("Detail")).toBeNull();
  });

  it("sets document.title while mounted and restores it on unmount", () => {
    const original = document.title;

    const { unmount } = render(
      <PageMetaProvider>
        <MetaSetter />
      </PageMetaProvider>,
    );
    expect(document.title).toBe("LSP-9001 · Bhawana Capital");

    unmount();
    expect(document.title).toBe(original);
  });

  it("settles instead of re-registering forever", () => {
    // `usePageMeta` must depend on the stable setter, not the whole context
    // value. Depending on the context object re-fires the effect on every
    // publish, which loops. A bounded render count is the guard.
    let commits = 0;
    function CountingSetter() {
      usePageMeta({ breadcrumbLabel: "LSP-9001", documentTitle: "LSP-9001 · Bhawana Capital" });
      // Counting after commit (not during render) keeps this a pure component.
      useEffect(() => {
        commits += 1;
      });
      return null;
    }

    render(
      <PageMetaProvider>
        <CountingSetter />
      </PageMetaProvider>,
    );

    expect(commits).toBeLessThanOrEqual(3);
  });
});
