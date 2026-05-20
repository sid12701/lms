import { describe, it, expect, vi } from "vitest";
import { axe } from "vitest-axe";
import { render } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

const navigateMock = vi.fn();

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

import { NotFoundPage } from "./not-found";

function renderNotFound() {
  return render(
    <MemoryRouter initialEntries={["/something-broken"]}>
      <NotFoundPage />
    </MemoryRouter>,
  );
}

describe("NotFoundPage", () => {
  it("renders an <h1> with accessible name 'Page not found'", () => {
    const { getByRole } = renderNotFound();
    const h1 = getByRole("heading", { level: 1, name: "Page not found" });
    expect(h1).toBeInTheDocument();
    expect(h1.tagName).toBe("H1");
  });

  it("navigates to /home when the 'Back to home' button is clicked", async () => {
    navigateMock.mockClear();
    const { getByRole } = renderNotFound();
    await userEvent.click(getByRole("button", { name: /back to home/i }));
    expect(navigateMock).toHaveBeenCalledTimes(1);
    expect(navigateMock).toHaveBeenCalledWith("/home");
  });

  it("has no axe violations", async () => {
    const { container } = renderNotFound();
    expect(await axe(container)).toHaveNoViolations();
  });
});
