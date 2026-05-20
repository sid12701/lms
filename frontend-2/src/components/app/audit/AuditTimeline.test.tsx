import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AuditTimeline } from "./AuditTimeline";
import { ALL_FIXTURES, FIXTURE_NOW } from "./fixtures";

describe("AuditTimeline", () => {
  it("renders an empty 'no-data' state when there are no events", () => {
    const { getByText, container } = renderWithProviders(
      <AuditTimeline events={[]} now={FIXTURE_NOW} />,
    );
    expect(getByText("No audit events yet")).toBeInTheDocument();
    expect(container.querySelector('[data-variant="no-data"]')).toBeInTheDocument();
  });

  it("renders a 'filtered-empty' state when filtered=true and events are empty", () => {
    const { getByText, container } = renderWithProviders(
      <AuditTimeline events={[]} filtered now={FIXTURE_NOW} />,
    );
    expect(getByText("No matching audit events")).toBeInTheDocument();
    expect(container.querySelector('[data-variant="filtered-empty"]')).toBeInTheDocument();
  });

  it("supports overriding empty title + description copy", () => {
    const { getByText } = renderWithProviders(
      <AuditTimeline events={[]} emptyTitle="Custom" emptyDescription="Try again" />,
    );
    expect(getByText("Custom")).toBeInTheDocument();
    expect(getByText("Try again")).toBeInTheDocument();
  });

  it("renders one node per event", () => {
    const { container } = renderWithProviders(
      <AuditTimeline events={ALL_FIXTURES} now={FIXTURE_NOW} />,
    );
    const nodes = container.querySelectorAll('[data-slot="audit-event-node"]');
    expect(nodes.length).toBe(ALL_FIXTURES.length);
  });

  it("orders most-recent first by default and inverts under order='asc'", () => {
    const desc = renderWithProviders(<AuditTimeline events={ALL_FIXTURES} now={FIXTURE_NOW} />);
    const descKinds = Array.from(
      desc.container.querySelectorAll('[data-slot="audit-event-node"]'),
    ).map((n) => n.getAttribute("data-kind"));
    expect(descKinds[0]).toBe("APPLICATION"); // 11:55 (latest)
    expect(descKinds[descKinds.length - 1]).toBe("PRODUCT"); // 07:00 (oldest)
    desc.unmount();

    const asc = renderWithProviders(
      <AuditTimeline events={ALL_FIXTURES} order="asc" now={FIXTURE_NOW} />,
    );
    const ascKinds = Array.from(
      asc.container.querySelectorAll('[data-slot="audit-event-node"]'),
    ).map((n) => n.getAttribute("data-kind"));
    expect(ascKinds[0]).toBe("PRODUCT");
    expect(ascKinds[ascKinds.length - 1]).toBe("APPLICATION");
  });

  it("propagates compact prop down to nodes", () => {
    const { container } = renderWithProviders(
      <AuditTimeline events={ALL_FIXTURES} compact now={FIXTURE_NOW} />,
    );
    expect(container.querySelector('[data-slot="audit-timeline"]')?.getAttribute("data-compact"))
      .toBe("true");
    const compactNodes = container.querySelectorAll('[data-compact="true"]');
    // 1 timeline + 6 nodes = 7
    expect(compactNodes.length).toBe(ALL_FIXTURES.length + 1);
  });

  it("has an accessible label and no axe violations when populated", async () => {
    const { container, getByLabelText } = renderWithProviders(
      <AuditTimeline events={ALL_FIXTURES} now={FIXTURE_NOW} />,
    );
    expect(getByLabelText("Audit timeline")).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});
