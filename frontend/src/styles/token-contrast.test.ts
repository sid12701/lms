/**
 * Token contrast verification — both themes, WCAG 2.1 AA.
 *
 * Replaces the former `dark-contrast.test.ts`, which hard-coded five
 * hand-picked dark values. That list omitted `foreground-subtle`, `warning`,
 * `revoked`, `info`, and `progress`, and only ever checked a token against the
 * flat surface — never against the 10% tint the UI actually renders it on
 * (`bg-warning/10 text-warning`). The suite passed while the running app failed
 * AA on 55 nodes, which is worse than having no test at all.
 *
 * This version reads `tokens.css` directly, so a token added or edited there is
 * covered automatically and cannot escape the gate by never being listed here.
 *
 * Thresholds (WCAG 2.1 SC 1.4.3):
 *   - AA normal text: >= 4.5
 *   - AA large text / non-text UI: >= 3.0
 */
// Vite's `?raw` import keeps this in the app's own module graph — no node types,
// no path resolution, and the test re-runs automatically when tokens.css changes.
import tokensCssRaw from "./tokens.css?raw";
import { describe, it, expect } from "vitest";

type Rgb = readonly [number, number, number];

// Comments are stripped first: the file's own header prose mentions `@theme`
// and `.dark { }`, which would otherwise anchor the block scan to a comment.
const TOKENS_CSS = tokensCssRaw.replace(/\/\*[\s\S]*?\*\//g, "");

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

/** Composite `fg` at `alpha` over `bg` — mirrors Tailwind's `bg-<token>/10`. */
function tint(fg: string, bg: string, alpha = 0.1): string {
  const f = parseHex(fg);
  const b = parseHex(bg);
  const mix = (i: number) => Math.round(f[i]! * alpha + b[i]! * (1 - alpha));
  return `#${[0, 1, 2].map((i) => mix(i).toString(16).padStart(2, "0")).join("")}`;
}

/**
 * Extract `--color-*: #rrggbb` declarations from a block of `tokens.css`.
 * Light values live in `@theme { ... }`; dark overrides in `.dark { ... }`.
 */
function extractBlock(marker: "@theme" | ".dark"): Record<string, string> {
  const start = TOKENS_CSS.indexOf(marker);
  if (start === -1) throw new Error(`Block not found in tokens.css: ${marker}`);
  const open = TOKENS_CSS.indexOf("{", start);
  let depth = 0;
  let end = open;
  for (let i = open; i < TOKENS_CSS.length; i += 1) {
    if (TOKENS_CSS[i] === "{") depth += 1;
    if (TOKENS_CSS[i] === "}") {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  const body = TOKENS_CSS.slice(open, end);
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--color-([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) {
    out[m[1]!] = m[2]!;
  }
  return out;
}

const LIGHT = extractBlock("@theme");
const DARK = { ...LIGHT, ...extractBlock(".dark") };

/**
 * Tokens used as *foreground* text. `surface`, `surface-muted`, `surface-raised`
 * and `border-*` are backgrounds and structure, so they are excluded.
 */
const FOREGROUND_TOKENS = [
  "success",
  "warning",
  "danger",
  "info",
  "progress",
  "revoked",
  "neutral",
  "foreground-muted",
  "foreground-subtle",
] as const;

/**
 * The seven semantic intents, which are the only tokens rendered as a tinted
 * badge (`bg-x/10 text-x`, see `StatusBadge`'s `subtle` variant). The two
 * `foreground-*` tokens are body text and never carry their own tint, so they
 * are held to the flat-background rule only.
 */
const INTENT_TOKENS = [
  "success",
  "warning",
  "danger",
  "info",
  "progress",
  "revoked",
  "neutral",
] as const;

/**
 * Every background a foreground token is actually painted on.
 *
 * The previous version of this file checked `--color-surface` and nothing else,
 * which is why it stayed green while live axe measured `success` at 4.44:1 and
 * `warning` at 4.30:1 on real screens. A badge sits on a panel, but panels sit
 * on the page, tables use the muted tier for header bands and hovered rows, and
 * `bg-x/10` is *translucent* — so the tint composites over whichever of those is
 * beneath it, and each darker base costs roughly 0.2 of a ratio point.
 */
function backgroundTiers(tokens: Record<string, string>) {
  const surface = tokens["surface"]!;
  const muted = tokens["surface-muted"]!;
  return [
    { name: "surface", value: surface },
    { name: "surface-page", value: tokens["surface-page"]! },
    { name: "surface-muted", value: muted },
    // A `DataTable` row paints `bg-surface-muted/40` over the panel on hover.
    { name: "row-hover", value: tint(muted, surface, 0.4) },
  ];
}

const THEMES = [
  { name: "light", tokens: LIGHT, surface: LIGHT["surface"]! },
  { name: "dark", tokens: DARK, surface: DARK["surface"]! },
] as const;

describe("token contrast (WCAG 2.1 AA, both themes)", () => {
  it("computes known fixtures correctly", () => {
    expect(contrastRatio("#ffffff", "#000000")).toBeCloseTo(21, 0);
    expect(contrastRatio("#abcdef", "#abcdef")).toBeCloseTo(1, 5);
  });

  it("parses both blocks out of tokens.css", () => {
    expect(Object.keys(LIGHT).length).toBeGreaterThan(5);
    expect(DARK["surface"]).not.toBe(LIGHT["surface"]);
  });

  for (const theme of THEMES) {
    describe(`${theme.name} theme`, () => {
      const tiers = backgroundTiers(theme.tokens);

      for (const name of FOREGROUND_TOKENS) {
        const value = theme.tokens[name];

        it(`${name} meets AA on every surface tier`, () => {
          expect(value, `--color-${name} missing from tokens.css`).toBeDefined();
          for (const tier of tiers) {
            expect(
              contrastRatio(value!, tier.value),
              `--color-${name} on ${tier.name} (${tier.value})`,
            ).toBeGreaterThanOrEqual(4.5);
          }
        });
      }

      for (const name of INTENT_TOKENS) {
        const value = theme.tokens[name];

        it(`${name} meets AA as a 10% tint on every surface tier`, () => {
          expect(value, `--color-${name} missing from tokens.css`).toBeDefined();
          for (const tier of tiers) {
            const bg = tint(value!, tier.value);
            expect(
              contrastRatio(value!, bg),
              `--color-${name} tinted over ${tier.name} (${tier.value} -> ${bg})`,
            ).toBeGreaterThanOrEqual(4.5);
          }
        });
      }
    });
  }

  /**
   * `text-primary-tinted` on `bg-primary/10` — the active sidebar item and the
   * internal scope chip. The preset's dark `--primary` is a *background* value;
   * using it as a foreground measured 2.23:1 before this token existed.
   */
  it("primary-tinted meets AA on a primary tint in dark", () => {
    const value = DARK["primary-tinted"];
    expect(value, "--color-primary-tinted missing from the .dark block").toBeDefined();
    const bg = tint(value!, DARK["surface"]!);
    expect(contrastRatio(value!, bg)).toBeGreaterThanOrEqual(4.5);
  });

  /**
   * The gate above can only protect tokens it can see, and it sees exactly one
   * file: `tokens.css`. The shadcn preset's role tokens live in `globals.css`
   * as `oklch()` literals, which this file neither reads nor can parse — so a
   * preset token used as foreground text escapes every check above.
   *
   * That is not hypothetical. `bg-destructive/10 text-destructive` shipped on
   * the button and badge destructive variants and measured **3.78:1** against a
   * 4.5:1 requirement, on "Mark invalid" and every other destructive action —
   * found by running axe against the live app, not by this suite.
   *
   * The token itself is fine where it belongs: `--destructive` on a solid
   * background, or as a border/ring under `aria-invalid`, is a background value
   * doing a background's job. The defect is using it as *text on its own tint*.
   * So the rule enforced here is the pattern, not the token: pair a
   * `bg-<token>/N` tint with `text-<token>` only for tokens `tokens.css`
   * declares, because only those are gated above.
   */
  describe("tinted foregrounds use a gated token", () => {
    const SOURCES = import.meta.glob("../components/**/*.{ts,tsx}", {
      eager: true,
      query: "?raw",
      import: "default",
    }) as Record<string, string>;

    it("finds component sources to scan", () => {
      expect(Object.keys(SOURCES).length).toBeGreaterThan(20);
    });

    it("no component pairs a destructive tint with destructive text", () => {
      const offenders: string[] = [];
      for (const [path, source] of Object.entries(SOURCES)) {
        // Same class string carrying both halves of the failing pattern.
        for (const cls of source.matchAll(/"([^"]*bg-destructive\/\d+[^"]*)"/g)) {
          if (/text-destructive/.test(cls[1]!)) offenders.push(path);
        }
      }
      expect(
        [...new Set(offenders)],
        "`text-destructive` on a `bg-destructive/N` tint measures 3.78:1 — below AA. " +
          "Use `danger`, which tokens.css declares and this suite gates on its own tint.",
      ).toEqual([]);
    });
  });

  /**
   * WCAG 2.1 SC 1.4.11 — non-text contrast.
   *
   * `--color-border-control` is the boundary on inputs, selects and outline
   * buttons. On a filter bar it is the *only* feature identifying the field, so
   * it is held to 3:1 against every surface tier a control can sit on. The
   * decorative `--border` hairline is deliberately exempt: it separates, it
   * does not identify.
   */
  describe("control boundary (SC 1.4.11, 3:1)", () => {
    for (const theme of THEMES) {
      const tiers = ["surface", "surface-muted", "surface-page"] as const;
      for (const tier of tiers) {
        it(`${theme.name}: border-control is discernible on ${tier}`, () => {
          const border = theme.tokens["border-control"];
          const bg = theme.tokens[tier];
          expect(border, "--color-border-control missing").toBeDefined();
          expect(bg, `--color-${tier} missing`).toBeDefined();
          expect(contrastRatio(border!, bg!)).toBeGreaterThanOrEqual(3);
        });
      }
    }
  });

  /**
   * The surface scale has to be a scale. Before 2026-08-03 the dark tiers
   * stepped 1.10 / 1.08 / 1.07 / 1.03 — nothing at 1.03:1 is perceivable, so
   * "raised" did not read as raised — while light collapsed to two distinct
   * values with `surface-raised` literally identical to `surface`.
   */
  describe("surface scale is perceivable", () => {
    for (const theme of THEMES) {
      it(`${theme.name}: every tier is a distinct value`, () => {
        const tiers = ["surface-page", "surface", "surface-muted", "surface-raised"] as const;
        const values = tiers.map((t) => theme.tokens[t]);
        // Light is a lifted-panel model: panels and raised panels are both
        // paper white and separate from the page, not from each other.
        const distinct = new Set(values);
        expect(distinct.size).toBeGreaterThanOrEqual(theme.name === "dark" ? 4 : 3);
      });
    }

    it("dark tiers step far enough apart to be seen", () => {
      const ladder = ["surface-page", "surface", "surface-muted", "surface-raised"] as const;
      for (let i = 1; i < ladder.length; i += 1) {
        const step = contrastRatio(DARK[ladder[i]!]!, DARK[ladder[i - 1]!]!);
        expect(step, `${ladder[i - 1]} -> ${ladder[i]}`).toBeGreaterThanOrEqual(1.12);
      }
    });
  });

  /**
   * Distinct meanings must not share a pixel value.
   *
   * In dark, `primary-tinted`, `info` and `progress` were all `#60a5fa`: brand,
   * "informational", and "in flight — do not act" rendered identically, in a
   * product whose hardest constraint is that in-flight means hands-off.
   */
  describe("semantic intents do not collide", () => {
    const MUST_DIFFER = [
      ["info", "progress"],
      ["info", "primary-tinted"],
      ["progress", "primary-tinted"],
      ["warning", "revoked"],
      ["success", "progress"],
      ["danger", "revoked"],
    ] as const;

    for (const theme of THEMES) {
      for (const [a, b] of MUST_DIFFER) {
        it(`${theme.name}: ${a} is not ${b}`, () => {
          expect(theme.tokens[a]).toBeDefined();
          expect(theme.tokens[b]).toBeDefined();
          expect(theme.tokens[a]).not.toBe(theme.tokens[b]);
        });
      }
    }
  });
});
