import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ApiSecretReveal } from "./ApiSecretReveal";

const REVEALED_VALUE = "example-api-credential";

describe("ApiSecretReveal", () => {
  let writeText: ReturnType<typeof vi.fn>;
  let originalClipboard: PropertyDescriptor | undefined;

  beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined);
    originalClipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
  });

  afterEach(() => {
    if (originalClipboard) {
      Object.defineProperty(navigator, "clipboard", originalClipboard);
    } else {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (navigator as any).clipboard;
    }
  });

  it("renders the warning, secret, copy and acknowledge buttons", () => {
    const { getByText, getByRole } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    expect(getByText(/Save this secret now/i)).toBeInTheDocument();
    expect(getByText(REVEALED_VALUE)).toBeInTheDocument();
    expect(getByRole("button", { name: /Copy secret to clipboard/i })).toBeInTheDocument();
    expect(getByRole("button", { name: /I.?ve saved it/i })).toBeInTheDocument();
  });

  it("includes the clientLabel in the description when provided", () => {
    const { getByText } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} clientLabel="Acme NBFC" />,
    );
    expect(getByText(/for Acme NBFC/i)).toBeInTheDocument();
  });

  it("calls clipboard.writeText with the secret when Copy is clicked", async () => {
    const { getByRole } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    await userEvent.click(getByRole("button", { name: /Copy secret to clipboard/i }));
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(REVEALED_VALUE);
  }, 15_000);

  it("temporarily switches to a Copied affordance after Copy is clicked", async () => {
    const { getByRole, findByText } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    const copyBtn = getByRole("button", { name: /Copy secret to clipboard/i });
    await userEvent.click(copyBtn);
    expect(await findByText("Copied")).toBeInTheDocument();
    expect(copyBtn.getAttribute("data-copied")).toBe("true");
  }, 15_000);

  it("calls onAcknowledge once when the acknowledge button is clicked", async () => {
    const onAcknowledge = vi.fn();
    const { getByRole } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={onAcknowledge} />,
    );
    await userEvent.click(getByRole("button", { name: /I.?ve saved it/i }));
    expect(onAcknowledge).toHaveBeenCalledTimes(1);
  }, 15_000);

  it("renders an aria-live region without reveal wording", () => {
    const { container } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    const live = container.querySelector('[data-slot="api-secret-announce"]');
    expect(live).not.toBeNull();
    expect(live?.getAttribute("aria-live")).toBe("polite");
    expect(live?.textContent).toMatch(/Secret available once\. Copy now\./i);
    expect(container.textContent).not.toMatch(/reveal/i);
  });

  it("still flips to Copied state when clipboard.writeText rejects", async () => {
    writeText.mockRejectedValueOnce(new Error("Permission denied"));
    const { getByRole, findByText } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    await userEvent.click(getByRole("button", { name: /Copy secret to clipboard/i }));
    // Even though the write rejected, the AT state-change still fires so
    // the operator gets feedback to fall back to manual selection.
    expect(await findByText("Copied")).toBeInTheDocument();
  }, 15_000);

  it("clears the prior copy-reset timer when Copy is clicked twice in quick succession", async () => {
    const { getByRole, findByText } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    const copyBtn = getByRole("button", { name: /Copy secret to clipboard/i });
    await userEvent.click(copyBtn);
    expect(await findByText("Copied")).toBeInTheDocument();
    // Second click: exercises the "clear previous timer" branch.
    await userEvent.click(copyBtn);
    expect(await findByText("Copied")).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledTimes(2);
  }, 15_000);

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <ApiSecretReveal secret={REVEALED_VALUE} onAcknowledge={() => {}} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
