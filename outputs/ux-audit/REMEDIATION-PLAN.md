# UX/UI Remediation — Options & Decisions

One section per issue: **Problem → Recommended → Alternatives → Effort/Files**. Pick per issue (or "recommended for all"). Then everything is implemented in one batch.

Status so far: **#1 (C1 mojibake)** ✅ done · **#2 (A1 schedule/repayments)** ✅ done (A kept, C reverted to raw message per your call). #1 *hardening* still open (below).

---

## 3 · B1 — Raw Zod validation messages
"String must contain at least 2 character(s)" shown to users. zod is **v3.25** → `z.setErrorMap` is available.
- **Recommended:** Install one **global Zod error map** at app startup (`main.tsx`/`App`) translating issue codes to friendly copy ("Required", "Must be at least N characters", "Enter a valid email"). Fixes **every** form at once; ~1 file.
- **Alt A:** Add explicit messages to each schema field (`z.string().min(2, "Enter a code…")`). More precise per-field copy, but touches ~15 schema files and is easy to miss new ones.
- **Alt B:** Global map **+** targeted per-field overrides only on the few high-traffic forms (LSP, User, Product). Best polish, slightly more work.
- **Effort:** Low (rec) → Med (Alt B). **Files:** new `lib/zod-error-map.ts` + `main.tsx`; (Alt B also a few `schema.ts`).

## 4 · E1 — Dead Notifications & Help buttons (no onClick)
- **Recommended:** **Wire them.** Bell → navigate to `/alerts` (existing surface); Help → small popover linking to keyboard shortcuts + docs (a `KbdHint` component already exists). Honest + cheap.
- **Alt A:** **Remove** both until a real notification/help system exists. Cleanest, least code, but loses two affordances.
- **Alt B:** Bell → `/alerts`; **remove** Help (no docs site yet).
- **Note:** Bell is internal-only (`/alerts` is SYSTEM_ADMIN/OPS); for LSP users it should hide or point elsewhere.
- **Effort:** Low. **Files:** `TopBar.tsx` (+ maybe a tiny Help popover).

## 5 · F1 — Dev "Sign in by role" panel on the login screen
- **Recommended:** Gate the whole `<section>` behind `import.meta.env.DEV` so it's stripped from production builds (same pattern the router already uses for `/dev/components`).
- **Alt A:** Keep it, but only render role buttons that have a configured preset (hides the "No user exists for this role" failures). Still ships dev affordance to prod — not ideal.
- **Alt B:** Move to a `?debug` query-flag opt-in.
- **Effort:** Low. **Files:** `LoginPage.tsx`.

## 6 · G2 — "Created 57 years ago" (epoch/seed timestamp)
- **Recommended:** Guard `formatRelative()` in `lib/format.ts` — if the date is invalid or implausibly old (e.g. year < 2000 / epoch), fall back to the absolute date (or "—"). Fixes it everywhere relative time is shown.
- **Alt A:** Fix only the seed/bootstrap user's `createdAt` in the backend. Removes this instance but leaves the formatter unguarded for any future bad date.
- **Alt B:** Both (guard + seed fix). Most robust.
- **Effort:** Low. **Files:** `lib/format.ts` (+ optional backend seed).

## 7 · D1 — US `mm/dd/yyyy` date inputs (India product)
Native `<input type=date>`; display format follows the document locale. `<html lang="en">` today.
- **Recommended:** Set `<html lang="en-IN">` (Chrome then renders **dd/mm/yyyy** for all native date inputs) — one-line, app-wide, also improves SR pronunciation.
- **Alt A:** `lang="en-GB"` (also dd/mm/yyyy; more universally supported than en-IN across browsers).
- **Alt B:** Build a shared styled date-picker component (e.g. shadcn calendar) and replace native inputs — full control of format/appearance, but Medium effort and touches 3 filter bars.
- **Effort:** Low (rec/Alt A) → Med (Alt B). **Files:** `index.html` (rec) — or new `DateField` + 3 filter bars (Alt B).

## 8 · D3 — Reports LSP filter = "paste a UUID"
- **Recommended:** Replace the text input with an **LSP name dropdown** reusing the same LSP-options source the Loan applications & Users filters already use. (The code comment saying the hook "isn't available yet" is stale — it exists now.)
- **Alt A:** Keep a text field but add a name→id typeahead (autocomplete) on top. More work, same outcome.
- **Alt B:** Leave as-is. Not recommended (hostile UX, inconsistent).
- **Effort:** Med. **Files:** `ReportsFilterBar.tsx` (+ reuse LSP-options hook).

## 9 · G1 — PII masking inconsistency  ⚠️ needs your policy call
Aadhaar is masked; **PAN + mobile (+ bank account) render in full** in list/detail. `lib/format.ts` claims "masking is the only presentation of PII," and a `maskAccount()` helper exists but isn't applied.
- **Recommended:** **Mask in list/directory views, show full in detail** for authorized internal roles; add `maskPan()`/`maskMobile()` helpers; apply consistently. Balances scannability/PII.
- **Alt A:** **Mask everywhere** with an explicit, audited "reveal" action (a PII-reveal audit stream already exists). Most compliant; most work.
- **Alt B:** **Leave full for internal ops** (status quo) and only document the policy. Least work; weakest privacy posture.
- **Effort:** Med (rec) → Med-High (Alt A). **Files:** `format.ts`, borrowers table/detail, loan-detail borrower panel. **(Coordinates with the tracked bank-account-unmasked bug.)**

## 10 · C2 — "Loan Service Provider" → "Lending Service Provider"
- **Recommended:** Fix the 3 strings (`lsps/page.tsx` subtitle + empty-state, `LspCreateDialog.tsx`) to the canonical CONTEXT.md term.
- **Alt:** None meaningful (it's a wording correction). Could also add a glossary lint later.
- **Effort:** Trivial. **Files:** 2.

## 11 · D4 — Reports "Portfolio preview" overflows; empty state mis-placed
17+ columns push the table off-screen; "No rows" lands far right.
- **Recommended:** Wrap the table in a horizontal-scroll container with a sensible min-width and **center the empty state** across the viewport (not the table's scroll width).
- **Alt A:** Add a column-visibility toggle / reduce default columns (the `DataTableViewOptions` component exists). Better long-term, more work.
- **Alt B:** Both.
- **Effort:** Low (rec) → Med (Alt B). **Files:** reports table component.

## 12 · D2 — Inconsistent filter controls  ⚠️ scope call
Loan-applications mixes a custom "All statuses" dropdown with native `<select>`s; status filtering appears as segmented control / dropdown / tabs across pages.
- **Recommended (scoped):** Make the **Loan applications + Borrowers** filter bars use the same design-system `Select` so adjacent controls match. Leave the segmented/tab patterns (they're legitimately different contexts).
- **Alt A (full):** Define one canonical filter-control set and unify every page. Most consistent; largest, riskiest pass.
- **Alt B:** Leave as-is.
- **Effort:** Med (rec) → High (Alt A). **Files:** `LoanApplicationsFilterBar.tsx`, borrowers filter, shared `FilterBar.tsx`.

## 13 · A3 — Disabled "Approve" reads as enabled
**Good news:** the button is already wrapped in `TransitionDisabledTooltip` (reason shows on hover/focus via a span wrapper). Remaining nit is visual: disabled green still looks clickable.
- **Recommended:** Tone down the disabled `approve` tone (muted/!no green when disabled) so it reads as inactive; keep the existing tooltip. Verify `DetailHeader` routes through `ActionBar` (so the tooltip is actually present on that screen).
- **Alt A:** Also surface the gate reasons inline next to the button (not only bottom-of-page).
- **Effort:** Low. **Files:** `ActionBar.tsx` (tone), verify `DetailHeader.tsx`.

## 14 · A2 — Activity feed shows raw enums
`AuditEventNode.summarize()` renders `INITIALIZED → AWAITING_APPROVAL` and raw `action` (e.g. `STATUS_TRANSITION`).
- **Recommended:** Humanize in `summarize()` — map statuses via the existing status-label source and prettify the action ("Status transition"). Localized, also benefits any timeline consumer.
- **Alt A:** Keep the enum but add a humanized label beside it (power-users keep the raw code).
- **Effort:** Low. **Files:** `AuditEventNode.tsx` (+ reuse status label map).

## 15 · E2 — 404 drops the app shell
The catch-all `{ path: "*" }` sits **outside** `AuthenticatedLayout` (router.tsx:194), so it renders bare.
- **Recommended:** Add a catch-all **inside** `AuthenticatedLayout`'s children so authenticated users keep sidebar/topbar; keep the bare top-level `*` for unauthenticated. Drop `min-h-screen` centering in the in-shell variant.
- **Alt A:** Single shared not-found that conditionally renders shell based on session. More logic in one place.
- **Effort:** Low-Med. **Files:** `router.tsx`, `not-found.tsx`.

## 16 · E3 — Tables not mobile-optimized  ⚠️ scope call (heaviest)
Wide tables horizontal-scroll on phones; key columns off-screen.
- **Recommended:** **Defer** unless mobile is a target persona — internal ops tooling is desktop-first. If pursued: priority columns + horizontal scroll affordance.
- **Alt A:** Full responsive **card layout** at small breakpoints for the main lists. High effort.
- **Effort:** Med-High. **Files:** `DataTable.tsx` + list pages.

## 17 · C3/C4 — Copy nits
"BHAW LOAN ID" / "Bhaw Loan ID" abbreviation; audit copy "…document **placeholders**".
- **Recommended:** "Bhawana loan ID"; "…documents". Trivial wording.
- **Effort:** Trivial. **Files:** loan-applications table/filter; audit copy source.

## 18 · H1 — Test/demo data hygiene (informational)
"Doc Borrower", 619-byte `pan.pdf`, etc. Not a code bug — confirm the prod seeding/cleanup story. No code change unless you want a demo-data guard.

---

## Cross-cutting · #1 hardening (mojibake recurrence) — open
- **Recommended:** Add `charset = utf-8` to `.editorconfig` **+** a CI grep guard (reuse `scratch/fix_mojibake.py --check`) so corruption can't return.
- **Alt:** editorconfig only (no CI gate) — lighter, weaker guarantee.
- **Effort:** Low.

---

## Suggested execution order (once you've picked)
1. Trivial/config: C2, C3/C4, F1, D1, G2, #1-hardening
2. Forms: B1
3. Reports: D3, D4
4. Detail polish: A2, A3
5. Shell: E1, E2
6. Scope-gated: D2, G1, E3
Then `npm run verify` (typecheck+lint+format+test+build) + live re-screenshots.
