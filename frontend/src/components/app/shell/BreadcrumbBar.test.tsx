import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { BreadcrumbBar } from "./BreadcrumbBar";
import { BREADCRUMB_LABELS, resolveBreadcrumbLabel } from "./breadcrumb-labels";

function renderAt(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <BreadcrumbBar />
    </MemoryRouter>,
  );
}

describe("BreadcrumbBar", () => {
  it("resolves loan-applications slug to display label 'Loan applications'", () => {
    const { getByText } = renderAt("/loan-applications");
    expect(getByText("Loan applications")).toBeInTheDocument();
  });

  it("resolves api-clients slug to 'API clients'", () => {
    const { getByText } = renderAt("/api-clients");
    expect(getByText("API clients")).toBeInTheDocument();
  });

  it("resolves my-loans slug to 'My loans'", () => {
    const { getByText } = renderAt("/my-loans");
    expect(getByText("My loans")).toBeInTheDocument();
  });

  it("renders nested mapped + opaque-id segments correctly", () => {
    const { getByText, getAllByText } = renderAt(
      "/loan-applications/0123456789abcdef0123456789abcdef",
    );
    // Loan applications becomes the linked penultimate crumb.
    expect(getByText("Loan applications")).toBeInTheDocument();
    // UUID-ish id becomes "Detail" (current page).
    const detailCrumbs = getAllByText("Detail");
    expect(detailCrumbs.length).toBeGreaterThan(0);
    const current = detailCrumbs.find((el) => el.getAttribute("aria-current") === "page");
    expect(current).toBeTruthy();
  });

  it("falls back to humanize() for an unknown segment", () => {
    const { getByText } = renderAt("/something-new");
    expect(getByText("Something New")).toBeInTheDocument();
  });

  it("exports a BREADCRUMB_LABELS lookup covering the documented top-level routes", () => {
    expect(BREADCRUMB_LABELS["loan-applications"]).toBe("Loan applications");
    expect(BREADCRUMB_LABELS["my-loans"]).toBe("My loans");
    expect(BREADCRUMB_LABELS["api-clients"]).toBe("API clients");
    expect(BREADCRUMB_LABELS["lsps"]).toBe("LSPs");
    expect(BREADCRUMB_LABELS["audit"]).toBe("Audit");
  });

  it("resolveBreadcrumbLabel returns 'Detail' for opaque id-like segments", () => {
    expect(resolveBreadcrumbLabel("abcdef0123456789abcdef0123456789")).toBe("Detail");
  });

  it("renders the Breadcrumb landmark with aria-current on the final crumb", () => {
    const { container } = renderAt("/lsps");
    expect(container.querySelector('nav[aria-label="Breadcrumb"]')).not.toBeNull();
    const current = container.querySelector('[aria-current="page"]');
    expect(current?.textContent).toBe("LSPs");
  });

  it("renders breadcrumb links with at least 24px hit targets", () => {
    const { getByRole } = renderAt("/loan-applications/0123456789abcdef0123456789abcdef");
    const homeLink = getByRole("link", { name: "Home" });
    expect(homeLink.className).toContain("min-h-6");
    const loanAppsLink = getByRole("link", { name: "Loan applications" });
    expect(loanAppsLink.className).toContain("min-h-6");
  });

  it("has no axe violations", async () => {
    const { container } = renderAt("/loan-applications");
    expect(await axe(container)).toHaveNoViolations();
  });
});
