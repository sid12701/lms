import { describe, it, expect } from "vitest";
import { axe } from "vitest-axe";
import { renderWithProviders } from "@/test/utils";
import { AuditEventNode } from "./AuditEventNode";
import {
  FIXTURE_APPLICATION,
  FIXTURE_INTAKE,
  FIXTURE_PII_LONG_REASON,
  FIXTURE_PII_SHORT_REASON,
  FIXTURE_DOCUMENT,
  FIXTURE_PRODUCT,
  FIXTURE_NOW,
} from "./fixtures";
import { getAuditCommon, getAuditTimestamp } from "./types";

function renderNode(entry: Parameters<typeof AuditEventNode>[0]["entry"], compact = false) {
  return renderWithProviders(
    <ul>
      <AuditEventNode entry={entry} compact={compact} now={FIXTURE_NOW} />
    </ul>,
  );
}

describe("AuditEventNode", () => {
  it("renders an APPLICATION row with the from→to transition", () => {
    const { getByText, container } = renderNode(FIXTURE_APPLICATION);
    expect(getByText("UNDER_REVIEW → APPROVED")).toBeInTheDocument();
    expect(getByText("approve")).toBeInTheDocument();
    expect(container.querySelector('[data-kind="APPLICATION"]')).toBeInTheDocument();
  });

  it("renders an INTAKE row with the canonical headline and no role", () => {
    const { getByText, container } = renderNode(FIXTURE_INTAKE);
    expect(getByText("Intake snapshot recorded")).toBeInTheDocument();
    // Intake events have null role; actor stamp must not include a "·".
    const actor = container.querySelector('[data-slot="audit-event-actor"]');
    expect(actor?.textContent).not.toContain("·");
  });

  it("renders a PII reveal row without exposing cleartext (reason wrapped in <details>)", () => {
    const { container, getByText } = renderNode(FIXTURE_PII_LONG_REASON);
    expect(getByText("Revealed PAN")).toBeInTheDocument();
    const details = container.querySelector('details[data-slot="audit-event-detail"]');
    expect(details).not.toBeNull();
    // Default closed: full reason not expanded; truncated summary visible.
    expect(details?.getAttribute("open")).toBeNull();
  });

  it("renders the PII reason inline (no <details>) when short", () => {
    const { container } = renderNode(FIXTURE_PII_SHORT_REASON);
    const details = container.querySelector('details[data-slot="audit-event-detail"]');
    expect(details).toBeNull();
    const inline = container.querySelector('p[data-slot="audit-event-detail"]');
    expect(inline?.textContent).toBe("ok");
  });

  it("renders DOCUMENT_ACCESS and PRODUCT rows with verb-based headlines", () => {
    const doc = renderNode(FIXTURE_DOCUMENT);
    expect(doc.getByText("Document downloaded")).toBeInTheDocument();
    doc.unmount();

    const product = renderNode(FIXTURE_PRODUCT);
    expect(product.getByText("Product mapping changed")).toBeInTheDocument();
  });

  it("renders the absolute + relative timestamp using the pinned `now`", () => {
    const { container } = renderNode(FIXTURE_APPLICATION);
    const time = container.querySelector('[data-slot="audit-event-timestamp"]');
    expect(time?.getAttribute("dateTime")).toBe(FIXTURE_APPLICATION.event.createdAt);
    const relative = container.querySelector('[data-slot="audit-event-relative"]');
    // 5 minutes between fixture timestamp (11:55) and FIXTURE_NOW (12:00)
    expect(relative?.textContent).toMatch(/5\s+minutes? ago/i);
  });

  it("shows the correlation id in a mono span with a hover title", () => {
    const { container } = renderNode(FIXTURE_APPLICATION);
    const corr = container.querySelector('[data-slot="audit-event-correlation"]');
    expect(corr?.getAttribute("title")).toBe(FIXTURE_APPLICATION.event.correlationId);
  });

  it("flips data-compact when compact=true", () => {
    const { container } = renderNode(FIXTURE_APPLICATION, true);
    expect(container.querySelector('[data-compact="true"]')).toBeInTheDocument();
  });

  it("getAuditCommon and getAuditTimestamp project the right fields per kind", () => {
    expect(getAuditCommon(FIXTURE_APPLICATION).actorRole).toBe("OPS_USER");
    expect(getAuditCommon(FIXTURE_INTAKE).actorRole).toBeNull();
    expect(getAuditTimestamp(FIXTURE_PII_LONG_REASON)).toBe(
      FIXTURE_PII_LONG_REASON.event.revealedAt,
    );
    expect(getAuditTimestamp(FIXTURE_DOCUMENT)).toBe(FIXTURE_DOCUMENT.event.accessedAt);
    expect(getAuditTimestamp(FIXTURE_PRODUCT)).toBe(FIXTURE_PRODUCT.event.createdAt);
    expect(getAuditTimestamp(FIXTURE_INTAKE)).toBe(FIXTURE_INTAKE.event.createdAt);
    expect(getAuditTimestamp(FIXTURE_APPLICATION)).toBe(FIXTURE_APPLICATION.event.createdAt);
  });

  it("has no axe violations across every stream kind", async () => {
    const { container } = renderWithProviders(
      <ul>
        <AuditEventNode entry={FIXTURE_APPLICATION} now={FIXTURE_NOW} />
        <AuditEventNode entry={FIXTURE_INTAKE} now={FIXTURE_NOW} />
        <AuditEventNode entry={FIXTURE_PII_LONG_REASON} now={FIXTURE_NOW} />
        <AuditEventNode entry={FIXTURE_DOCUMENT} now={FIXTURE_NOW} />
        <AuditEventNode entry={FIXTURE_PRODUCT} now={FIXTURE_NOW} />
      </ul>,
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
