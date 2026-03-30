# Design System Strategy: The Sovereign Ledger

This design system is engineered for **Bhawana Capital** to bridge the gap between institutional authority and modern digital agility. We move away from the "bootstrap" look of generic financial portals, opting instead for a **High-End Editorial** aesthetic. 

The system prioritizes "The Sovereign Ledger" as its Creative North Star—a philosophy where data isn't just displayed; it is curated. By utilizing intentional asymmetry, sophisticated tonal layering, and a departure from traditional borders, we create a workspace that feels like a premium physical ledger translated into a high-performance digital engine.

---

## 1. Creative North Star: The Sovereign Ledger
In high-end finance, clarity is the ultimate luxury. This design system rejects the "boxed-in" feeling of traditional dashboards. Instead of rigid grids separated by lines, we use **Tonal Depth** and **Negative Space** to define hierarchy. 

- **Intentional Asymmetry:** Use large `display-sm` headlines offset against dense `body-sm` data tables to create a visual rhythm that guides the eye.
- **Atmospheric Depth:** The UI should feel layered, like sheets of heavy-stock vellum and frosted glass stacked atop one another.

---

## 2. Color & Surface Philosophy
The palette is rooted in a deep, authoritative navy (`primary: #000666`), balanced by a sophisticated grayscale that favors soft, cool neutrals over harsh whites.

### The "No-Line" Rule
**Prohibit 1px solid borders for sectioning.** Use background shifts to define boundaries.
- **Surface:** `#f8f9fa` (The base canvas).
- **Surface-Container-Low:** `#f3f4f5` (Subtle nesting for secondary modules).
- **Surface-Container-Highest:** `#e1e3e4` (Deeply recessed areas, like search bars or inactive buckets).

### The Glass & Gradient Rule
To prevent a "flat" appearance, primary actions and floating elements must feel tactile:
- **Signature Gradients:** Use a linear gradient from `primary` (#000666) to `primary_container` (#1a237e) at a 135° angle for primary CTAs.
- **Glassmorphism:** Floating modals or dropdowns should use `surface_container_lowest` (#ffffff) at 85% opacity with a `20px` backdrop blur.

---

## 3. Typography: The Editorial Scale
We pair **Manrope** (for structural authority) with **Inter** (for data precision).

| Role | Token | Font | Specs | Intent |
| :--- | :--- | :--- | :--- | :--- |
| **Hero Figures** | `display-sm` | Manrope | 2.25rem / Bold | Total Portfolio Value / Key Metrics |
| **Section Header** | `headline-sm` | Manrope | 1.5rem / SemiBold | High-level module titling |
| **Data Label** | `label-sm` | Inter | 0.6875rem / Medium | Caps / 0.05em tracking for table headers |
| **Financial Data** | `body-md` | Inter | 0.875rem / Regular | Tabular data and transaction details |

---

## 4. Elevation & Depth
Hierarchy is achieved through **Tonal Layering** rather than structural lines.

- **The Layering Principle:** Place a `surface_container_lowest` card (Pure White) on a `surface_container_low` background. This creates a "natural lift."
- **Ambient Shadows:** For elevated elements (e.g., a hovered card), use a shadow with a 24px blur, 4% opacity, using the `on_surface` color (#191c1d). This mimics natural light.
- **Ghost Borders:** If an edge must be defined (e.g., in high-density tables), use `outline_variant` (#c6c5d4) at **15% opacity**. Never use 100% opaque lines.

---

## 5. Components

### Cards & Layout Modules
- **Rule:** No dividers. Use `spacing.8` (1.75rem) to separate content blocks.
- **Styling:** Use `rounded.lg` (0.5rem) for all containers. Background should be `surface_container_lowest`.

### Data Tables
The heart of the Bhawana Capital dashboard.
- **Header:** Use `surface_container_high` for the header row background. Typography: `label-sm` in `on_surface_variant`.
- **Row Separation:** No horizontal lines. Use a `surface_container_low` background fill on `:hover` to highlight the active row.
- **Status Badges:** 
    - *Success:* `tertiary_fixed_dim` (#88d982) background with `on_tertiary_fixed` (#002204) text.
    - *Warning/Error:* `error_container` (#ffdad6) background with `on_error_container` (#93000a) text.
    - *Shape:* `rounded.full` with `spacing.1` vertical and `spacing.3` horizontal padding.

### Action Buttons
- **Primary:** Gradient fill (Navy to Indigo). Text: `on_primary` (#ffffff). `rounded.md`.
- **Secondary:** Surface: `surface_container_high`. Text: `primary`. No border.
- **Tertiary (Ghost):** No background. Text: `primary`. Transition to a 10% opacity `primary` background on hover.

### Financial Input Fields
- **Container:** `surface_container_low`.
- **Active State:** A "Ghost Border" of `primary` at 40% opacity and a `spacing.px` thickness.
- **Label:** Floating `label-sm` inside the container for space-saving density.

---

## 6. Do’s and Don’ts

### Do
- **Do** use `surface_container_highest` for "sunken" interactive elements like search inputs.
- **Do** allow for generous white space around `display-md` typography to emphasize the premium "Editorial" feel.
- **Do** use `tertiary` greens for positive growth trends, ensuring they are legible against the light surface backgrounds.

### Don’t
- **Don’t** use black (#000000) for text. Use `on_surface` (#191c1d) for a softer, more professional contrast.
- **Don’t** use standard 1px borders to separate table rows or sidebar sections; use background color shifts of 2-4% tonal difference.
- **Don’t** use harsh drop shadows. If it doesn't look like ambient light, it doesn't belong in this system.

---

## 7. Spacing Logic
Utilize the **8pt-derived scale** for all layouts to ensure mathematical harmony.
- **Standard Padding:** `spacing.5` (1.1rem) for internal card padding.
- **Section Gaps:** `spacing.10` (2.25rem) or `spacing.12` (2.75rem) to create clear breathing room between disparate data modules.