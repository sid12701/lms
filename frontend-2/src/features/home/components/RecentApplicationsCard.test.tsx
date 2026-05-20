import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { MemoryRouter } from "react-router-dom";
import { renderWithProviders } from "@/test/utils";
import { DensityProvider } from "@/app/providers";
import type { HomeRecentApplication } from "../types";
import { RecentApplicationsCard } from "./RecentApplicationsCard";

const navigateMock = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

const FIXTURE: HomeRecentApplication[] = [
  {
    id: "11111111-1111-4111-8111-111111111111",
    externalLoanId: "EXT-001",
    borrowerNameMasked: "A•••a Devi",
    lspName: "Bhawana Capital",
    productName: "Personal Loan",
    status: "AWAITING_APPROVAL",
    requestedAmount: 250_000,
    createdAt: new Date(Date.now() - 1000 * 60 * 60).toISOString(),
    assignedToName: null,
  },
  {
    id: "22222222-2222-4222-8222-222222222222",
    externalLoanId: null,
    borrowerNameMasked: "R•••h Kumar",
    lspName: "Acme LSP",
    productName: "Two-Wheeler Loan",
    status: "DISBURSED",
    requestedAmount: 75_000,
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 5).toISOString(),
    assignedToName: "Ops Lead",
  },
];

function wrap(node: React.ReactNode) {
  return (
    <DensityProvider>
      <MemoryRouter>{node}</MemoryRouter>
    </DensityProvider>
  );
}

describe("<RecentApplicationsCard />", () => {
  beforeEach(() => {
    navigateMock.mockReset();
  });

  it("renders the card title", () => {
    const { getByText } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} />),
    );
    expect(getByText("Recent applications")).toBeInTheDocument();
  });

  it("renders a row per application", () => {
    const { getByText } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} />),
    );
    expect(getByText("A•••a Devi")).toBeInTheDocument();
    expect(getByText("R•••h Kumar")).toBeInTheDocument();
    expect(getByText("Personal Loan")).toBeInTheDocument();
    expect(getByText("Bhawana Capital")).toBeInTheDocument();
  });

  it("renders an empty state when applications is empty", () => {
    const { getByText, queryByRole } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={[]} />),
    );
    expect(getByText("No recent applications")).toBeInTheDocument();
    // Ensure no table rendered.
    expect(queryByRole("table")).toBeNull();
  });

  it("navigates to the application detail on row click", async () => {
    const user = userEvent.setup();
    const { getByText } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} />),
    );
    const row = getByText("A•••a Devi").closest("tr");
    expect(row).not.toBeNull();
    await user.click(row!);
    expect(navigateMock).toHaveBeenCalledWith(
      `/loan-applications/${FIXTURE[0]!.id}`,
    );
  });

  it("navigates on keyboard Enter", async () => {
    const user = userEvent.setup();
    const { getByText } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} />),
    );
    const row = getByText("R•••h Kumar").closest("tr") as HTMLTableRowElement;
    row.focus();
    await user.keyboard("{Enter}");
    expect(navigateMock).toHaveBeenCalledWith(
      `/loan-applications/${FIXTURE[1]!.id}`,
    );
  });

  it("forwards className to the card", () => {
    const { container } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} className="extra-class" />),
    );
    const card = container.querySelector('[data-slot="recent-applications"]');
    expect(card).not.toBeNull();
    expect(card!.className).toContain("extra-class");
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={FIXTURE} />),
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it("has no axe violations when empty", async () => {
    const { container } = renderWithProviders(
      wrap(<RecentApplicationsCard applications={[]} />),
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
