---
name: Bhawana LMS
description: An instrument panel for supervising loans that originate elsewhere — dense, exact, and unsentimental.
colors:
  primary: "oklch(0.5 0.134 242.749)"
  primary-foreground: "oklch(0.977 0.013 236.62)"
  background: "#f7f9fc"
  foreground: "oklch(0.148 0.004 228.8)"
  card: "oklch(1 0 0)"
  muted: "oklch(0.963 0.002 197.1)"
  muted-foreground: "oklch(0.5 0.021 213.5)"
  accent: "oklch(0.963 0.002 197.1)"
  border: "oklch(0.925 0.005 214.3)"
  input: "#7e8796"
  ring: "oklch(0.5 0.134 242.749)"
  destructive: "oklch(0.577 0.245 27.325)"
  surface: "#ffffff"
  surface-muted: "#eef1f7"
  surface-raised: "#ffffff"
  border-control: "#7e8796"
  border-strong: "#cfd5e3"
  foreground-muted: "#5e6680"
  foreground-subtle: "#666c79"
  success: "#0f7a4a"
  warning: "#8b6816"
  danger: "#b23a48"
  info: "#1f4ec9"
  progress: "#5836a6"
  revoked: "#7a5a18"
  neutral: "#5e6680"
  primary-tinted: "#0069a8"
  card-muted: "#eef1f7"
typography:
  display:
    fontFamily: "Figtree Variable, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 600
    lineHeight: "2rem"
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Figtree Variable, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: "1.25rem"
    letterSpacing: "normal"
  body:
    fontFamily: "Figtree Variable, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.625
    letterSpacing: "normal"
  body-lg:
    fontFamily: "Figtree Variable, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.375rem"
    letterSpacing: "normal"
  label:
    fontFamily: "Figtree Variable, sans-serif"
    fontSize: "0.6875rem"
    fontWeight: 600
    lineHeight: "1rem"
    letterSpacing: "0.08em"
  mono:
    fontFamily: "JetBrains Mono, ui-monospace, SFMono-Regular, Menlo, monospace"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: "1.25rem"
    fontFeature: "tabular-nums"
rounded:
  control: "0.25rem"
  container: "0.5rem"
  card: "0.75rem"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  panel: "20px"
  page: "24px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "0 8px"
    height: "28px"
  button-primary-hover:
    backgroundColor: "color-mix(in oklch, oklch(0.5 0.134 242.749) 80%, transparent)"
  button-outline:
    backgroundColor: "transparent"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "0 8px"
    height: "28px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.foreground-muted}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "0 8px"
    height: "28px"
  button-destructive:
    backgroundColor: "color-mix(in oklch, oklch(0.577 0.245 27.325) 10%, transparent)"
    textColor: "{colors.destructive}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "0 8px"
    height: "28px"
  status-badge-subtle:
    backgroundColor: "color-mix(in srgb, currentColor 10%, transparent)"
    typography: "{typography.label}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
    height: "20px"
  card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.card}"
    padding: "16px 0"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.container}"
    padding: "20px"
  input:
    backgroundColor: "color-mix(in oklch, #7e8796 20%, transparent)"
    borderColor: "{colors.border-control}"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "2px 8px"
    height: "28px"
  sidebar-item-active:
    backgroundColor: "color-mix(in oklch, oklch(0.5 0.134 242.749) 10%, transparent)"
    textColor: "{colors.primary}"
    typography: "{typography.body-lg}"
    rounded: "{rounded.control}"
    padding: "8px 12px"
---

# Design System: Bhawana LMS

## Overview

**Creative North Star: "The Instrument Panel"**

Nothing in this product originates here. Loans are created by partner systems over the API; state updates, disbursement outcomes, and repayment events arrive from outside and collate on the platform. The interface is the readout — it reports what those upstream systems have said, aggregates it into KPI snapshots and timely reports, and withholds any control that would be unsafe to press given the state on the panel. A panel that lies, or that offers a lever attached to nothing, is worse than no panel.

The register is sober, dense, and exact, in the way a regulated financial record is exact. Information per square inch is deliberately high: controls are 28px tall, body copy is 12px, and badges are 20px, because every pixel spent on chrome is a pixel not spent on the rows an operator reads all day. Money is set in monospaced figures that align by digit down a column, so a wrong magnitude is visible without being read. Colour appears only where it carries meaning, and never carries meaning alone.

It is not friendly and it is not trying to be. It rejects the consumer-fintech register — no gradient hero cards, no illustrated empty-state mascots, no celebratory motion when a loan disburses — and equally rejects legacy core-banking grime: no grey-on-grey chrome, no bevels, no unlabeled icon toolbars, no illegible sub-12px grids. The target sits between the two: modern, legible, and completely unsentimental.

**Key Characteristics:**

- Instrumentation density — small controls, high row count, no wasted vertical rhythm
- Monospaced, tabular figures for every comparable number
- Flat surfaces separated by hairlines, not stacked planes
- One blue, spent sparingly, on the action that matters
- Semantic status expressed as colour **plus** icon **plus** text, always
- Light and dark are equal citizens, both held to WCAG 2.1 AA

## Colors

A near-neutral cool-grey field with a single institutional blue accent and a seven-intent semantic vocabulary that does the real communicating.

### Primary

- **Institutional Steel Blue** (`oklch(0.5 0.134 242.749)`): The only brand accent. It marks the active navigation item (as a 10% tint with a 2px left bar), the single consequential action in a dialog or page header, and focus affordances. Sober and deliberately unexciting — it reads as banking-sector, not as a product launch. In dark mode it **lifts** to `oklch(0.55 0.125 242.749)`.

  That last point reverses an earlier rule that the accent should *deepen* in dark "so it never glows". Deepening put the fill at 2.38:1 against the dark surface — below the 3:1 non-text threshold of WCAG 1.4.11 — so a primary button's own shape was not discernible, while all seven semantic intents brightened to the Tailwind-400 ramp. The restraint had been applied to the accent and the brightness to the semantics, exactly inverted. The lifted value measures 3.55:1 on the surface with near-white text on it at 4.79:1.

  **Foreground counterpart.** `--primary` is a *background* value. Text on a primary tint uses `--color-primary-tinted`; `text-primary` on `bg-primary/10` measures 2.24:1 in dark and must not be used.

### Neutral

- **Paper White** (`#ffffff`): Panel and card fill. Panels sit *on* the page rather than being the page — see Soft Page below.
- **Soft Page** (`#f7f9fc`): The page background. Light used to be pure white at every tier, so `background`, `surface` and `surface-raised` were three names for one value and a panel could only be found by its hairline. A panel now separates from the page by value as well as by border.
- **Cool Wash** (`#eef1f7`): The `surface-muted` tier — table header rows, hovered rows, keyboard hint chips, and the resting state of neutral badges.
- **Ink** (`oklch(0.148 0.004 228.8)`): Primary text.
- **Slate Grey** (`#5e6680`): The `foreground-muted` tier — secondary text, table meta columns, axis labels, inactive nav items.
- **Hairline** (`oklch(0.925 0.005 214.3)`): Dividers, table rules, panel edges. Decorative separation, exempt from 1.4.11.
- **Control Boundary** (`#7e8796`): The *affordance* border on inputs, selects and outline buttons, held to 3:1 against every surface tier. WCAG 1.4.11 requires 3:1 for the visual information that identifies a control, and on a filter bar the boundary is the only such feature — the old 1.25:1 hairline was a formal failure.
- **Strong Hairline** (`#cfd5e3`): The escalated border, reserved for hover on interactive cards and neutral badge outlines.

Dark mode replaces the surface tiers with navy-tinted blacks rather than neutral greys, which keeps the enterprise-banking character instead of drifting to a generic dark theme. **All five tiers are one hue (250°) at one chroma (0.022), stepping only in lightness:** `#070f18` page / `#141d26` surface / `#212a33` muted / `#2e3842` raised.

Two invariants hold that scale together, both of which were broken before 2026-08-03:

- **One family.** `--card`, `--popover` and `--sidebar` derive from this scale rather than carrying their own values. They used to be near-neutral charcoal while `--surface` was navy; both were white in light, so the split was invisible until you switched themes — and then every dropdown, popover and dialog reverted to grey floating over navy panels.
- **A live ladder.** Steps are +6 / +5.5 / +5.5 L\*, matching Material 3's dark elevation ladder. The old steps measured 1.10 / 1.08 / 1.07 / 1.03; nothing at 1.03:1 is perceivable, so "raised" did not read as raised.

### Semantic Intents

Seven named intents, defined in `tokens.css` and lifted in saturation for dark mode. These are not decorative colours and must not be reused as a palette:

- **Success** (`#0f7a4a`): Paid, closed, credited, healthy.
- **Warning** (`#8b6816`): Partially paid, attention needed, not yet failing. Was `#a67c1a`, which measured 3.80:1 on white and 3.40:1 on its own tint — below AA.
- **Danger** (`#b23a48`): Overdue, rejected, failed. Also DPD counters.
- **Info** (`#1f4ec9`): Neutral informational state.
- **Progress** (`#5836a6`): In flight, awaiting, pending — the state where the operator must not act.
- **Revoked** (`#7a5a18`): Invalidated or withdrawn, distinct from rejected.
- **Neutral** (`#5e6680`): Unknown, not applicable, or drift.

**No two intents may share a pixel value.** Progress used to *be* `#1f4ec9` — byte-identical to Info — and in dark it collapsed further, with Info, Progress and the brand accent all rendering `#60a5fa`. Brand, "informational", and "in flight, do not act" were one colour, in a product whose hardest constraint is that in-flight means hands-off. Progress is now violet, separated from Info by hue *and* luminance. `token-contrast.test.ts` fails the build on any new collision.

Every intent clears AA against its theme's surface **and** against its own 10% tint — the `bg-x/10 text-x` badge pattern the app actually renders — in both themes. That gate is enforced, not aspirational: the suite reads `tokens.css` directly, so a token added or edited there is covered automatically.

### Named Rules

**The One Blue Rule.** Institutional Steel Blue marks at most one action per view. If two buttons on a screen are blue, one of them is wrong. Everything else is `outline` or `ghost`.

**The Never Colour Alone Rule.** No state is communicated by hue alone. Every status badge, installment row, and alert pairs its colour with an icon and a text label (WCAG 1.4.1). This is enforced in `StatusBadge` and `InstallmentRow` and asserted in tests — treat it as an invariant, not a preference.

**The Reserved Red Rule.** Danger and destructive are reserved for money that failed or a record that will be lost. Never use red for emphasis, never for a count, never to make a metric look important.

**The Unaligned Chart Ramp.** `--chart-1` through `--chart-5` are a red-orange ramp inherited from the shadcn preset. They are **not** aligned to this system's blue primary and are effectively unused. Do not reach for them when adding a visualization; derive series colours from the primary and the semantic intents instead.

## Typography

**Display / Body Font:** Figtree Variable (with `sans-serif` fallback), served locally via `@fontsource-variable/figtree`
**Numeric / Mono Font:** JetBrains Mono (with `ui-monospace, SFMono-Regular, Menlo` fallback)

**Character:** Figtree is a humanist geometric sans — open apertures, unfussy, legible at the very small sizes this system leans on. It carries no editorial personality, which is the point: the type gets out of the way of the figures. JetBrains Mono handles every number that a person might compare against another number, and the pairing is the system's clearest signal that this is an instrument rather than a document.

### Hierarchy

- **Display** (600, 1.5rem / 2rem, `-0.025em`): The page `h1` in `PageHeader`. One per screen. Truncates rather than wraps.
- **Title** (500, 0.875rem / 1.25rem): Card and panel headings. Deliberately close to body size — a heading here is a label, not a statement.
- **Body-lg** (400, 0.875rem / 1.375rem): Page and section descriptions, table cell text, form values. Capped at `max-w-2xl` for description copy.
- **Body** (400, 0.75rem / 1.625): The dominant size across the app — buttons, card content, secondary text, most chrome. 12px is the floor for anything a person reads in sequence.
- **Label** (600, 0.6875rem / 1rem, `0.08em`, uppercase): The eyebrow above page and section titles, standardized in `PageEyebrow` and tokenised as `--text-eyebrow` / `text-eyebrow`. The only uppercase text in the system.
- **Badge** (500, 0.75rem / 1rem): Badge and pill content, tokenised as `--text-badge` / `text-badge`. Was 10px, which was the single root cause of 25 `undersized-ui-text` findings on `/loan-applications` alone and sat below this system's own stated floor.

Both roles were previously written out as arbitrary values (`text-[11px] font-semibold tracking-[0.08em] uppercase`, `text-[0.625rem]`) at every call site, which is how the eyebrow acquired four spellings and the badge drifted below the floor. Reach for the token, not the literal.
- **Mono** (400, tabular figures): Amounts, IDs, dates, percentages, and every numeric table column, via `TabularNumber` or the `data-tabular` attribute.

### Named Rules

**The Tabular Money Rule.** Any figure that could be compared against another figure in a column is monospaced with `font-variant-numeric: tabular-nums`. Amounts, loan IDs, dates, DPD counters, percentages. A number set in the proportional face is a bug in a ledger.

**The Rupee Rule.** Currency renders through `formatINR` with `en-IN` grouping (lakh/crore placement), not Western thousands grouping. Two decimals on installment-level money, zero on aggregates.

**The One Voice Rule.** Uppercase is reserved for the 11px eyebrow. Do not uppercase buttons, table headers, badges, or nav items. Table headers were violating this app-wide until 2026-08-03; they are now 12px medium in `--foreground-muted`, separated from their column by weight, colour and the muted header band rather than by shouting.

## Layout

A fixed left rail, a fluid content column, and an optional right rail — the classic three-zone operator frame, with the rails degrading before the data does.

**The shell.** A 256px sidebar (`w-64`) at ≥1280px, collapsing to a 64px icon-only rail (`w-16`) between 1024px and 1280px, and becoming a focus-trapped slide-over drawer below 1024px. A 56px sticky top bar (`h-14`) carries the scope chip, user menu, and theme toggle. A breadcrumb bar sits as the first child inside `main`. An optional 288px right rail (`w-72`) appears only at ≥1280px and is hidden entirely below that — context, never content.

**Breakpoints.** The three tiers that matter are `lg` (1024px) and `xl` (1280px), resolved in JS by `useViewportTier` so the shell can switch component trees rather than just CSS. Desktop is the designed target; below `lg` the layout stays usable but is not where the work happens.

**Page rhythm.** Pages are a vertical flex column with 24px padding and 24px gaps between sections (`gap-6 p-6`). Panels carry 20px internal padding; cards carry 16px. The KPI strip is a responsive grid — 1 column at base, 2 at `md`, 4 at `xl`, 16px gutters throughout.

**Density is a user setting, and it defaults to `compact`.** A global `comfortable` / `compact` toggle persists to `localStorage` and drives table row density across the app. Any new dense surface must honour it rather than hard-coding row padding. The default follows the north star: shipping `comfortable` put 6 of 25 rows on a 1280×800 viewport, on a product whose whole argument is information per square inch. A stored preference always wins; only the first run is opinionated.

**Tables own their scroll.** A data table's scroll container is bounded to the viewport minus the shell chrome, so its `thead` can actually stick. `position: sticky` resolves against the nearest scrolling ancestor: when the table sat inside two nested `overflow-x-auto` wrappers with no bounded height, the header resolved against a container that never scrolled and simply left with the page — gone by row ~14 of 25. There must be exactly one scroller, and it must have a height.

### Named Rules

**The Instrument Density Rule.** Controls shrink so data doesn't. When a screen runs out of room, reduce chrome — button size, padding, rail width — before reducing the number of rows or columns visible.

**The Rails Are Optional Rule.** Both the sidebar and the right rail may disappear at narrow widths without loss of function. Nothing essential — no primary action, no required datum — may live only in a rail.

## Elevation & Depth

This system is **flat, with hairline separation**. Depth is carried by 1px borders, a `ring-1` at 10% foreground on cards, and the three-step surface scale — not by stacked shadows. The heaviest shadow token in the file is never used, and that is correct: shadows on a dense grid add visual noise without adding meaning, and every extra plane is another edge the eye has to resolve while scanning rows.

The single exception is content that genuinely leaves the plane: dialogs, popovers, tooltips, and the focus-revealed skip link. Those may lift, because they are physically above the page rather than part of it.

### Shadow Vocabulary

- **Hairline lift** (`box-shadow: 0 1px 2px rgba(0, 6, 102, 0.06)`): The default and near-only shadow. Applied to panels, tables, KPI tiles, form sections, and keyboard hint chips. It is a hairline, not an elevation — its job is to separate a panel from the page by a fraction of a value step.
- **Overlay lift** (`box-shadow: 0 4px 12px rgba(0, 6, 102, 0.08)`): Reserved for things that leave the plane. Currently only the focus-revealed skip-to-main link.
- **Deep lift** (`box-shadow: 0 12px 28px rgba(0, 6, 102, 0.14)`): Defined but unused. Do not introduce it without a reason that survives the Hairline Rule.

All three are navy-tinted rather than neutral black in **light**, so shadows sit in the same cool family as the surfaces. **Dark redefines them as near-black with higher alpha** (`rgba(0, 0, 0, 0.45 / 0.55 / 0.65)`). A navy shadow authored for white paper is invisible on a `#141d26` surface; until 2026-08-03 the light values rendered unchanged in dark, which is why dark had no working elevation at all.

### Named Rules

**The Hairline Rule.** Separation is a 1px border or ring. If a new surface needs a shadow to be distinguishable from the page, its background or border is wrong.

## Shapes

Rectilinear and calm. Corners are softened enough to feel current but never enough to read as playful. **Radius encodes scale**: the bigger the thing, the rounder its corners, so shape tells you what kind of object you are looking at before you read it.

Three semantic radii, named for their job rather than for a step on a numeric ramp, plus the pill:

- **4px** (`rounded-control`) — buttons, inputs, selects, chips, menu items, icon tiles. Anything 20–32px tall.
- **8px** (`rounded-container`) — panels, tables, filter bars, dialogs, popovers, empty states.
- **12px** (`rounded-card`) — `Card` surfaces: independent objects in a dashboard grid.
- **Full** (`9999px`) — badges and status pills, exclusively. A pill shape in this system means "this is a state", and nothing else may claim it.

This replaces a single-value scheme in which `rounded-md` (11.2px) accounted for 103 of ~162 radius usages and, on one screen, covered a 24px select, a 32px input, the filter panel and an 858px table container identically — so shape carried no information at all. The root cause was a ratio, not a value: shadcn's `--radius` is tuned for 36px controls, and this system kept the larger root while halving control heights. A 24px `SelectTrigger` at 11.2px is 93% of the way to a pill, which collided with the Pill Means State Rule 200px from a real `StatusBadge`.

The values are integers on purpose. 11.2 and 8.4 are fractional and rasterise inconsistently at 1×.

For calibration: Atlassian puts buttons at 6px and reserves its largest step for modals and full-page containers; Fluent uses 4px and drops sub-32px components to 2px; Carbon's tables are square. A 4px control sits inside that consensus rather than outside it.

Borders are 1px. `--border` is the decorative hairline (dividers, table rules, panel edges); `--border-control` is the affordance boundary on form controls and is held to 3:1; `--border-strong` is the escalated border on hover of an interactive card. There is no clipping, no angled geometry, no decorative shape language. The active-navigation marker — a 2px full-height bar pinned to the left edge of the item — is the one piece of non-rectangular signature geometry.

### Named Rules

**The Three Radius Rule.** Controls are 4px, containers 8px, cards 12px, badges pills. A fourth radius needs a reason. (Supersedes the Two Radius Rule, which was documented as two and implemented as seven.)

**The Pill Means State Rule.** Fully-rounded corners signal status and nothing else. Never make a button or an input a pill.

## Components

### Buttons

- **Shape:** 4px (`rounded-control`) at every size. Buttons are controls, so they take the control radius; the old scheme gave a 28px default button an 11.2px radius — 80% of the way to a pill.
- **Sizing:** Compact and unfussy by design — the default is 28px tall (`h-7`) with 8px horizontal padding and 12px text. The scale runs 20px (`xs`) / 24px (`sm`) / 28px (default) / 32px (`lg`), with matching square icon variants. These are half the height of stock shadcn buttons; that is intentional and must be preserved.
- **Primary:** Solid Institutional Steel Blue with near-white text; hover drops to 80% opacity. One per view.
- **Outline:** Transparent with a hairline border — the default for row-level and secondary actions.
- **Ghost:** No border, no fill; hover reveals a muted background. Used for toolbar and icon actions.
- **Destructive:** A 10% danger tint with danger-coloured text and no solid fill. Destructive actions are legible, not loud.
- **States:** Focus draws a 2px ring at 30% opacity plus a border shift. Active nudges the button down 1px (`translate-y-px`) — the system's only tactile flourish, and it's suppressed on menu triggers. Disabled drops to 50% opacity and removes pointer events.

### Status Badges (signature component)

The component that carries the most meaning in the product. `StatusBadge` maps any lifecycle status to one of seven intents, then renders intent colour + a status-specific icon + a text label.

- **Style:** 20px tall pill, 10px medium text, 12px icon, 8px horizontal padding.
- **Subtle (default):** 10% intent tint background, 30% intent border, intent-coloured text and icon. This is what appears in tables and detail headers.
- **Default (solid):** Solid intent fill with white text. Reserved for emphasis, used sparingly.
- **Unknown handling:** A status the frontend doesn't recognize renders with a question-mark icon and an explicit `Unknown (raw)` label rather than being silently mapped to a known state. Drift is made visible.
- **Never** render this component with `hideIcon` in a context where colour is the only remaining differentiator.

### Cards and Panels

Two container idioms coexist, and the distinction is worth keeping deliberate:

- **Card** (shadcn primitive): 14px radius, `ring-1` at 10% foreground, **no border and no shadow**, 16px vertical padding, 16px content padding, 12px body text. A `sm` size drops to 12px gaps and padding. Used for dashboard and KPI content.
- **Panel** (`PageSection`, `FormSection`, table wrappers): 8px radius, 1px `--border` border, hairline shadow, 20px padding, `--surface` background. Used for page sections, forms, and data tables.

Prefer the panel idiom for anything that wraps a table or a form; prefer the card for dashboard composition.

### Inputs and Fields

- **Style:** 28px tall, 4px radius, 1px `--border-control` border at 3:1, and a very faint 20% input-colour fill (30% in dark) — filled rather than transparent, so a field is identifiable as a target before it's focused.
- **Focus:** Border shifts to `--ring` and a 2px ring appears **at full opacity**. `--ring` is the primary blue: 5.86:1 on a panel, 5.55:1 on the page, clearing the 3:1 floor of SC 2.4.11 and 1.4.11 on every surface tier. It was previously a light grey measuring 2.44:1 at full strength and rendered at `/30`–`/50`, i.e. 1.51:1 as actually drawn — a formal failure on a keyboard-driven product. **Never reintroduce a fractional ring opacity in a focus context.** Components that draw their own ring opt out of the global outline so the two do not stack; components that do not, inherit a 2px outline at 2px offset.

- **Filter controls:** every leaf control in a filter bar renders from one spec (`FILTER_CONTROL_CLASS`) — 28px, 12px text, control radius, one border token — so a row of them shares a baseline. Bars previously mixed 24/32/36px heights and 12/14px type, including two heights interleaved on a single row 4px apart.
- **Applied state is visible.** A set filter carries a primary tint, a primary border and medium weight — three channels, not one. A set control and an unset one used to be pixel-identical, so three active filters looked exactly like zero. Applied filters are additionally named in a chip row with per-filter clear and a result count: in a lending queue, not knowing whether you are seeing the whole book or a slice is a correctness problem, not a cosmetic one.
- **Error:** `aria-invalid` drives a destructive border plus a 20% destructive ring. Errors are announced structurally, not only coloured.
- **Disabled:** 50% opacity, `not-allowed` cursor, pointer events removed.

### Navigation

- **Sidebar items:** 14px medium text, 4px radius, 12px horizontal / 8px vertical padding, 16px icon, 12px gap. Inactive is muted-grey text on transparent; hover fills with `surface-muted` and darkens the text. **Active** is a 10% primary tint, primary-coloured text and icon, and a 2px primary bar pinned to the left edge — the signature nav treatment.
- **Collapsed rail:** icon centred, label moved to `sr-only`. The accessible name never disappears.
- **Mobile:** the same `Sidebar` renders inside a Dialog-based slide-over with a focus trap and Escape handling, so exactly one `aside` with the "Primary navigation" landmark exists in the a11y tree at any time.
- **Transitions:** colour only, 150ms.

### Data Tables

- Wrapped in the panel idiom with `overflow-hidden`.
- Numeric columns opt into tabular figures via `TABULAR_ATTR`; money is right-aligned, labels left-aligned.
- Row density follows the global `comfortable` / `compact` setting.
- The loan-applications grid additionally tightens `line-height` to 1.4 and enables tabular numerics on every cell via a `[data-page]` scope.
- Column visibility, sorting, and pagination live in shared `DataTable*` components; mobile falls back to `DataTableMobileCards` rather than a horizontally scrolling grid.

## Do's and Don'ts

### Do:

- **Do** keep controls small so data stays large — 28px buttons, 12px body, 20px badges. Density is the product's defining visual characteristic.
- **Do** set every comparable figure in JetBrains Mono with tabular numerics, via `TabularNumber` or `TABULAR_ATTR`.
- **Do** pair every status colour with an icon and a text label, without exception.
- **Do** separate surfaces with a 1px border or ring, and reach for a shadow only when the element genuinely floats above the page.
- **Do** reserve the pill radius for status, and the primary blue for the single most consequential action on the view.
- **Do** design light and dark together, and verify contrast against the AA thresholds pinned in `src/styles/dark-contrast.test.ts` — a palette edit that breaks contrast is expected to break the build.
- **Do** honour the global density setting on any new dense surface.
- **Do** surface unrecognized values explicitly (`Unknown (raw)`) rather than mapping them onto a known state.

### Don't:

- **Don't** introduce gradients, glows, glassmorphism, decorative illustration, or celebratory motion. There is no gradient anywhere in this system and there should not be one.
- **Don't** use `--chart-1` through `--chart-5` for new visualizations; they are an unaligned red ramp inherited from the preset.
- **Don't** put two primary-blue buttons on the same view.
- **Don't** use a semantic intent colour decoratively — success green is "paid", not "nice".
- **Don't** uppercase anything other than the 11px eyebrow.
- **Don't** scale a button up to make it feel important; importance is carried by colour and position, not size.
- **Don't** add a fourth corner radius, a fourth shadow, or a neutral grey outside the surface and foreground tiers.
- **Don't** make an in-flight or unsafe action look pressable. If the money state forbids it, the control is absent or explicitly disabled with a reason — never merely styled down.
