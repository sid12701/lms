import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { ApiClientSecretMeta } from "./ApiClientSecretMeta";

const CREATED_AT = "2026-01-15T10:30:00.000Z";
const ROTATED_AT = "2026-04-02T05:15:00.000Z";

describe("ApiClientSecretMeta", () => {
  it("renders all metadata fields", () => {
    const { getByText } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={ROTATED_AT}
        ipAllowlistCount={3}
      />,
    );
    expect(getByText("ak_live_8f2b")).toBeInTheDocument();
    expect(getByText(/Created/i)).toBeInTheDocument();
    expect(getByText(/Last rotated/i)).toBeInTheDocument();
    expect(getByText(/IP allow-list/i)).toBeInTheDocument();
    expect(getByText(/3 entries/i)).toBeInTheDocument();
  });

  it("formats the created and rotated timestamps with the IST suffix", () => {
    const { getAllByText, getByText } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={ROTATED_AT}
        ipAllowlistCount={1}
      />,
    );
    // Both dates render with the trailing IST literal — exact local
    // formatting depends on the runner's TZ, so assert the suffix only.
    const matches = getAllByText(/IST$/m);
    expect(matches.length).toBe(2);
    expect(getByText(/1 entry/i)).toBeInTheDocument();
  });

  it("renders 'Never rotated' when lastRotatedAt is null", () => {
    const { getByText, getAllByText } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={null}
        ipAllowlistCount={0}
      />,
    );
    expect(getByText("Never rotated")).toBeInTheDocument();
    // createdAt still rendered with IST suffix; only one date now.
    expect(getAllByText(/IST$/m).length).toBe(1);
    expect(getByText(/0 entries/i)).toBeInTheDocument();
  });

  it("hides the rotate button when onRotate is not provided", () => {
    const { queryByRole } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={null}
        ipAllowlistCount={2}
      />,
    );
    expect(queryByRole("button", { name: /Rotate secret/i })).not.toBeInTheDocument();
  });

  it("shows the rotate button and invokes onRotate when clicked", async () => {
    const onRotate = vi.fn();
    const { getByRole } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={ROTATED_AT}
        ipAllowlistCount={2}
        onRotate={onRotate}
      />,
    );
    await userEvent.click(getByRole("button", { name: /Rotate secret/i }));
    expect(onRotate).toHaveBeenCalledTimes(1);
  }, 15_000);

  it("falls back to the raw value when the timestamp is unparseable", () => {
    const { getByText } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt="not-a-date"
        lastRotatedAt={null}
        ipAllowlistCount={0}
      />,
    );
    expect(getByText("not-a-date")).toBeInTheDocument();
  });

  it("has no axe violations", async () => {
    const { container } = renderWithProviders(
      <ApiClientSecretMeta
        keyIdPrefix="ak_live_8f2b"
        createdAt={CREATED_AT}
        lastRotatedAt={ROTATED_AT}
        ipAllowlistCount={3}
        onRotate={() => {}}
      />,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
