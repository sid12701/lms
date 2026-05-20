import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { MaskedField } from "./MaskedField";

const PAN_VALUE = "ABCDE1234F";
const PAN_MASKED = "ABCDE••••F";

describe("MaskedField", () => {
  it("renders the masked value by default", () => {
    const { getByText, queryByText } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={() => {}} />,
    );
    expect(getByText(PAN_MASKED)).toBeInTheDocument();
    expect(queryByText(PAN_VALUE)).not.toBeInTheDocument();
  });

  it("opens the reveal dialog when Reveal is clicked", async () => {
    const { getByRole, findByRole } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={() => {}} />,
    );
    await userEvent.click(getByRole("button", { name: /Reveal PAN/i }));
    expect(await findByRole("heading", { name: /Reveal PAN/i })).toBeInTheDocument();
  });

  it("closes the dialog when the user dismisses without confirming", async () => {
    const onReveal = vi.fn();
    const { getByRole, findByRole, queryByRole } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={onReveal} />,
    );
    await userEvent.click(getByRole("button", { name: /Reveal PAN/i }));
    await findByRole("heading", { name: /Reveal PAN/i });
    await userEvent.click(getByRole("button", { name: "Cancel" }));
    expect(queryByRole("heading", { name: /Reveal PAN/i })).not.toBeInTheDocument();
    expect(onReveal).not.toHaveBeenCalled();
  });

  it("calls onReveal with the reason + idempotencyKey, then shows the cleartext", async () => {
    const onReveal = vi.fn().mockResolvedValue(undefined);
    const { getByRole, findByLabelText, findByText } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={onReveal} />,
    );
    await userEvent.click(getByRole("button", { name: /Reveal PAN/i }));
    const reasonInput = await findByLabelText(/Reason for revealing/i);
    await userEvent.type(reasonInput, "Verifying borrower KYC.");
    await userEvent.click(getByRole("button", { name: "Reveal" }));

    expect(onReveal).toHaveBeenCalledTimes(1);
    const [args] = onReveal.mock.calls[0]!;
    expect(args.reason).toBe("Verifying borrower KYC.");
    expect(typeof args.idempotencyKey).toBe("string");

    expect(await findByText(PAN_VALUE)).toBeInTheDocument();
    expect(getByRole("button", { name: /Hide PAN/i })).toBeInTheDocument();
  }, 15_000);

  it("returns to masked state when Hide is clicked", async () => {
    const onReveal = vi.fn().mockResolvedValue(undefined);
    const { getByRole, findByLabelText, findByText, queryByText } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={onReveal} />,
    );
    await userEvent.click(getByRole("button", { name: /Reveal PAN/i }));
    await userEvent.type(await findByLabelText(/Reason for revealing/i), "ok");
    await userEvent.click(getByRole("button", { name: "Reveal" }));
    await findByText(PAN_VALUE);

    await userEvent.click(getByRole("button", { name: /Hide PAN/i }));
    expect(queryByText(PAN_VALUE)).not.toBeInTheDocument();
    expect(getByRole("button", { name: /Reveal PAN/i })).toBeInTheDocument();
  }, 15_000);

  it("uses the masked override when provided", () => {
    const { getByText } = renderWithProviders(
      <MaskedField
        fieldName="MOBILE"
        value="9876543210"
        masked="•••–•••–3210"
        onReveal={() => {}}
      />,
    );
    expect(getByText("•••–•••–3210")).toBeInTheDocument();
  });

  it.each([
    ["AADHAAR", "123456781234", "XXXX XXXX 1234"],
    ["MOBILE", "9876543210", "••••••3210"],
    ["ACCOUNT_NUMBER", "12345678903456", "••••••••••3456"],
    ["EMAIL", "user@example.com", "u•••@example.com"],
  ] as const)("masks %s values with the matching format helper", (fieldName, value, expected) => {
    const { getByText } = renderWithProviders(
      <MaskedField fieldName={fieldName} value={value} onReveal={() => {}} />,
    );
    expect(getByText(expected)).toBeInTheDocument();
  });

  it("has no axe violations in the masked state", async () => {
    const { container } = renderWithProviders(
      <MaskedField fieldName="PAN" value={PAN_VALUE} onReveal={() => {}} />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
