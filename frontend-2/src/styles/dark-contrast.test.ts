/**
 * Dark-mode WCAG AA contrast verification.
 *
 * Asserts that the key foreground/background pairings in the `.dark { ... }`
 * block of `tokens.css` meet WCAG 2.1 minimum contrast ratios. The literal
 * hex values are duplicated here intentionally: parsing tokens.css at runtime
 * is out of scope for a unit test, and pinning the values here means any
 * future palette edit that breaks contrast also breaks this test.
 *
 * WCAG 2.1 relative luminance is computed per the spec:
 *   https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 *
 * Thresholds:
 *   - AA normal text:  ratio >= 4.5
 *   - AAA normal text: ratio >= 7
 *   - AA large text / non-text UI components: ratio >= 3
 */
import { describe, it, expect } from "vitest";

type Rgb = readonly [number, number, number];

function parseHex(hex: string): Rgb {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) throw new Error(`Invalid hex color: ${hex}`);
  const v = parseInt(m[1]!, 16);
  return [(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff] as const;
}

function channel(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHex(hex);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const [lighter, darker] = la >= lb ? [la, lb] : [lb, la];
  return (lighter + 0.05) / (darker + 0.05);
}

// Dark palette — semantic-intent + surface tiers from `.dark { ... }` in
// src/styles/tokens.css. Role tokens (primary/accent/border/etc.) are owned
// by the shadcn preset in globals.css and use oklch values that this
// hex-only test infra cannot evaluate; those pairings are validated visually
// against the preset's published contrast guarantees instead.
const DARK = {
  surface: "#0f1729",
  surfaceMuted: "#161e36",
  foregroundMuted: "#9aa3bd",
  success: "#34d399",
  danger: "#f87171",
} as const;

describe("dark-mode contrast (WCAG AA/AAA)", () => {
  it("relativeLuminance + contrastRatio compute against known fixtures", () => {
    // White on black is the canonical 21:1 reference.
    expect(contrastRatio("#ffffff", "#000000")).toBeCloseTo(21, 0);
    // Identical colours are 1:1.
    expect(contrastRatio("#abcdef", "#abcdef")).toBeCloseTo(1, 5);
  });

  it("muted foreground on surface meets AA (>= 4.5)", () => {
    const ratio = contrastRatio(DARK.foregroundMuted, DARK.surface);
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });

  it("success on surface meets AA (>= 4.5)", () => {
    const ratio = contrastRatio(DARK.success, DARK.surface);
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });

  it("danger on surface meets AA (>= 4.5)", () => {
    const ratio = contrastRatio(DARK.danger, DARK.surface);
    expect(ratio).toBeGreaterThanOrEqual(4.5);
  });
});
