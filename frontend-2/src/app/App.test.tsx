import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { App } from "./App";

describe("App", () => {
  it("renders the router and lands on /login when no session is present", async () => {
    // No persisted session → unauthenticated → LandingRedirect → /login.
    window.localStorage.removeItem("bhawana-lms-session");
    render(<App />);
    expect(
      await screen.findByRole("heading", { level: 1, name: /loan management/i }),
    ).toBeInTheDocument();
  });
});
