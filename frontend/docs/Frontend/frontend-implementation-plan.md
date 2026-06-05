# Frontend Implementation Plan — Bhawana LMS

**This document is the single source of truth for all frontend implementation work.** Agents must read it before starting and must not improvise alternatives without escalation.

Companion documents:
- `docs/BRD-executive-brief.md` — delivered-state product brief, business rules BR-1…BR-15
- `docs/lms-blueprint.md` — engineering blueprint
- `docs/UI pages.md` — existing UI surface (for context only — we are rebuilding, not preserving)
- `CLAUDE.md` — repo-level Claude guidance + locked decisions

---

## 0. Locked Decisions

| # | Decision | Notes |
|---|---|---|
| D1 | **Tailwind v4** (CSS-first, no `tailwind.config.js`) | Use `@tailwindcss/vite`. Theme lives in `src/styles/tokens.css` via `@theme`. |
| D2 | **Fonts via Google Fonts CDN** | Inter (sans) + JetBrains Mono. Linked from `index.html`, not `@import`. `display=swap`. |
| D3 | **npm** (not pnpm, not yarn) | Lockfile committed. Node ≥ 20 LTS. |
| D4 | `/audit` is a new SYSTEM_ADMIN-only page | Surfaces all five audit streams. |
| D5 | `/my-webhooks` deferred | Out of initial build; do not implement. |
| D6 | Right-rail kept on detail pages | Sticky 288px ≥ xl breakpoint, collapses to a tab below. |
| D7 | Density default = **comfortable** | Use compact only where absolutely necessary (24+ row repayment schedules, MIS preview, audit log). Never dense as default. |
| D8 | **Fuller test coverage** from day 1 | Vitest + RTL + vitest-axe + Playwright. Coverage gates per layer. |
| D9 | Dark mode shipped in **Phase 10** | Tokens declared from the start; values + verification deferred. |
| D10 | Color direction preserved | Navy `#000666` (brand-700) + warm gold `#b48e4b` (accent-500) carry over from existing UI. |

---

## 1. Product Context (one-paragraph version for agents)

Internal NBFC operations console + LSP partner workspace. State-machine-driven loan lifecycle from intake to closure with five tamper-evident audit streams, multi-tenant isolation by `lspId`, role-based access, and idempotent mutations. The frontend ships entirely against an in-app mock API; no real network calls. Every screen must be drop-in swappable to the real Spring Boot backend later, so mock function signatures must mirror the real contracts described in BRD §5 and blueprint §10.

**Audiences:**
- Internal: `SYSTEM_ADMIN`, `OPS_USER`, `PRODUCT_ADMIN` — default landing `/home`
- LSP: `LSP_UI_READ`, `LSP_UI_WRITE` — default landing `/my-loans`
- API-only role (`LSP_API_CLIENT`) has no UI; managed via `/api-clients`

---

## 2. Stack (locked)

```
React            19.x
TypeScript       5.5+ (strict, noUncheckedIndexedAccess)
Vite             5.4+
Tailwind         4.0+ (with @tailwindcss/vite)
shadcn/ui        Tailwind-v4 compatible
React Router     6.26+ (data router APIs)
TanStack Query   5.x
TanStack Table   8.x
React Hook Form  7.x + @hookform/resolvers
Zod              3.23+
Recharts         2.12+
Lucide React     latest
date-fns         3.x
class-variance-authority, clsx, tailwind-merge, sonner, cmdk
```

**Test stack:** Vitest 2 + jsdom + RTL + user-event + vitest-axe + @axe-core/react + MSW 2 + Playwright 1.45+.
**Lint/format:** ESLint 9 (flat config) + typescript-eslint + react-hooks + jsx-a11y + Prettier 3 + prettier-plugin-tailwindcss + husky + lint-staged.

---

## 3. Repository Layout (final)

```
.
├─ .github/workflows/ci.yml
├─ .husky/pre-commit
├─ .nvmrc                      # 20.11
├─ .editorconfig · .prettierrc.json · .gitignore
├─ eslint.config.js
├─ index.html                  # Google Fonts <link>s
├─ package.json · package-lock.json
├─ playwright.config.ts
├─ vite.config.ts              # @tailwindcss/vite + alias @/* + vitest inline config
├─ vitest.setup.ts             # jest-dom + axe matchers
├─ tsconfig.json · tsconfig.node.json
├─ components.json             # shadcn config
├─ docs/                       # this folder
├─ public/
└─ src/
   ├─ app/                     # App.tsx, providers.tsx, error-boundary.tsx, axe-runtime.tsx
   ├─ routes/                  # router.tsx, guards.tsx, landing-redirect.tsx, not-found.tsx
   ├─ components/
   │  ├─ ui/                   # shadcn output — DO NOT hand-edit unless restyling
   │  └─ app/                  # composed app components (see §11)
   ├─ features/
   │  ├─ auth/ home/ loan-applications/ borrowers/ lsp-loans/
   │  ├─ lsps/ products/ users/ api-clients/ alerts/ reports/ audit/
   ├─ mocks/                   # see §10
   ├─ schemas/                 # Zod schemas (source of truth)
   ├─ types/                   # z.infer<> re-exports
   ├─ lib/                     # format.ts, lifecycle.ts, role-gates.ts, permissions.ts,
   │                           #   url-state.ts, idempotency.ts, tabular-nums.ts, utils.ts
   ├─ hooks/
   ├─ test/                    # utils.tsx (renderWithProviders), a11y.ts
   ├─ styles/                  # globals.css, tokens.css
   ├─ main.tsx
   └─ vite-env.d.ts
```

**Strict folder rules:**
- shadcn output goes only in `components/ui` and is committed.
- App-specific composition lives in `components/app` — never inside `components/ui`.
- Feature pages live under `features/<feature>/` with subfolders `components/`, `hooks/`, optionally `schemas/` for feature-local form schemas.
- Cross-feature components belong in `components/app`.
- Domain types are inferred from Zod schemas; no hand-written interfaces.

---

## 4. Information Architecture

### Internal sidebar
```
WORKSPACE
  Home                  /home
  Loan applications     /loan-applications
  Alerts                /alerts          (SYSTEM_ADMIN, OPS_USER)
REPORTING
  Portfolio MIS         /reports         (SYSTEM_ADMIN)
ADMINISTRATION
  LSPs                  /lsps            (SYSTEM_ADMIN)
  Loan products         /products        (SYSTEM_ADMIN, PRODUCT_ADMIN)
  Users                 /users           (SYSTEM_ADMIN)
  API clients           /api-clients     (SYSTEM_ADMIN)
  Audit log             /audit           (SYSTEM_ADMIN)
```

### LSP sidebar
```
WORKSPACE
  Home                  /home   (link cards)
  My loans              /my-loans
```
(`/my-webhooks` deferred per D5.)

### Layout shell (≥ xl 1280px)
```
┌──────────────────────────────────────────────────────────┐
│  TopBar (sticky 56px) — brand · ⌘K search · scope chip · │
│                          notifications · help · user     │
├──────────┬───────────────────────────────────────────────┤
│          │  Breadcrumb (32px)                            │
│ Sidebar  ├───────────────────────────────────────────────┤
│ 264px    │  Page Header (sticky on scroll, action bar)   │
│          ├───────────────────────────────────────────────┤
│          │  Content (max-w 1320px, 24px gutters)         │
│          │  + optional Right Rail 288px on detail pages  │
└──────────┴───────────────────────────────────────────────┘
```
Sidebar collapses to icon-only at 1024–1279, slide-over Sheet < 1024. Right-rail collapses to a tab < 1280.

### Detail-page tabs (URL-bound via `?tab=`)

- **Loan Application Detail:** Overview · Schedule · Documents · Repayments · Activity · Webhooks
- **Borrower Detail:** Profile · Loans · Activity

---

## 5. Design System — Tokens

### 5.1 Color (light defaults; dark variant declared, values stubbed for P10)

```css
@theme {
  /* Brand */
  --color-brand-50:  #f0f2fb;
  --color-brand-100: #dde1f4;
  --color-brand-500: #1f2a8a;
  --color-brand-700: #000666;     /* PRIMARY — preserve */
  --color-brand-900: #000333;
  --color-accent-500: #b48e4b;    /* WARM GOLD — preserve */
  --color-accent-700: #8a6a32;

  /* Surface */
  --color-background: #f6f7fb;
  --color-surface: #ffffff;
  --color-surface-muted: #f6f7fb;
  --color-surface-raised: #ffffff;
  --color-border: #e5e8f0;
  --color-border-strong: #cfd5e3;

  /* Foreground */
  --color-foreground: #0f1729;
  --color-foreground-muted: #5e6680;
  --color-foreground-subtle: #8a92a8;

  /* Roles */
  --color-primary: var(--color-brand-700);
  --color-primary-foreground: #ffffff;
  --color-ring: #1f2a8a;

  /* Semantic intents (color + icon + text — never color alone) */
  --color-success: #0f7a4a;
  --color-warning: #a67c1a;
  --color-danger:  #b23a48;
  --color-info:    #1f4ec9;
  --color-progress:#1f4ec9;
  --color-revoked: #7a5a18;
  --color-neutral: #5e6680;
}

.dark { /* Phase 10 — tokens to be filled */ }
```

### 5.2 Typography

- **Family:** `Inter` (sans, weights 400/500/600/700) + `JetBrains Mono` (weights 500/600). Loaded via Google Fonts CDN in `index.html` with `display=swap` and preconnect.
- `font-variant-numeric: tabular-nums` on all numeric table columns (use `data-tabular` attribute or utility class).

| Role | Size / Line | Weight | Use |
|---|---|---|---|
| Display | 30/36 | 700 | Auth hero only |
| H1 (page) | 24/32 | 600 | Page titles |
| H2 (section) | 18/28 | 600 | Section headers |
| H3 (sub) | 16/24 | 600 | Card titles |
| Eyebrow | 11/16 0.08em uppercase | 600 | Section eyebrows |
| Body | 14/22 | 400 | Default body, table cells |
| Body-lg | 16/24 | 400 | Reading-priority body |
| Caption | 12/16 | 500 | Metadata, helper |
| Mono | 13/20 | 500 | Amounts, IDs, PAN, account no., dates |

### 5.3 Spacing / radius / elevation

- Spacing base **4px**: `0 1 2 3 4 5 6 8 10 12 16 20 24` × 4.
- Radius: `--radius-sm 4 · --radius 6 (default) · --radius-md 8 · --radius-lg 12 · --radius-xl 16`.
- Shadows (navy-tinted):
  - `--shadow-e1: 0 1px 2px rgba(0,6,102,.06)`
  - `--shadow-e2: 0 4px 12px rgba(0,6,102,.08)`
  - `--shadow-e3: 0 12px 28px rgba(0,6,102,.14)`

### 5.4 Density

`data-density` on `<html>`: `comfortable` (default), `compact`, `dense`. Persisted in `localStorage`. Affects table cell padding and form field height. Default `comfortable` (D7); compact only on the long-list surfaces explicitly listed in §7.

### 5.5 Status / lifecycle color groups

| Group | Statuses | Intent token |
|---|---|---|
| Origination | INITIATED, KYC_PENDING, DOCS_PENDING | neutral |
| Underwriting | UNDER_REVIEW, AWAITING_APPROVAL | progress |
| Approval | APPROVED, APPROVED_PENDING_DISBURSAL | progress |
| Disbursement | DISBURSEMENT_IN_PROGRESS | warning |
|  | DISBURSED | success |
| Servicing | UNDER_REPAYMENT, PARTIALLY_PAID | success |
| Delinquency | DELINQUENT (DPD-bucketed) | warning → danger |
| Closure | FORECLOSED, CLOSED, FULLY_REPAID | neutral |
| Failure | REJECTED, CANCELLED | danger |
|  | INVALIDATED | revoked |

DPD buckets: 0=neutral · 1–30=info · 31–60=warning · 61–90=warning-strong · 90+=danger. Always color + icon + text.

---

## 6. Lifecycle State Machine

Centralized in `src/lib/lifecycle.ts`:

```ts
export const STATUS_META: Record<LoanStatus, StatusMeta>;     // label, intent, group, icon, open
export const TRANSITIONS: TransitionRule[];                   // from, to, allowedRoles, preconditions, label, intent
export function getNextActions(status, role, ctx): TransitionRule[];
export function canTransition(role, from, to, ctx): Result<void, BusinessRuleError>;
```

The mock router calls the **same** `canTransition()` during `transitionStatus()` — identical client-side gating and server-side enforcement. ActionBar renders only `getNextActions(...)`; failed preconditions surface as **disabled-with-tooltip** explaining *why* (e.g., "KYC pending — 2 unverified documents") instead of hiding the button.

---

## 7. Page Inventory (with density assignment)

| Route | Density | Notes |
|---|---|---|
| `/login` `/change-password` | comfortable | Auth shell |
| `/home` (internal) | comfortable | Dashboard |
| `/home` (LSP) | comfortable | Link cards |
| `/my-loans` | comfortable | List + detail drawer |
| `/loan-applications` | comfortable | Triage queue table |
| `/loan-applications/:id` | mixed — Schedule tab uses **compact** | All other tabs comfortable |
| `/borrowers/:id` | comfortable | Loans table compact if > 12 rows |
| `/lsps` `/products` `/users` `/api-clients` | comfortable | Admin pages |
| `/alerts` | comfortable | Filter tabs + table |
| `/reports` | **compact** (preview table is the page reason-for-being) | KPI strip stays comfortable |
| `/audit` | **compact** | High-volume log surface |

---

## 8. Component Architecture (`src/components/app/`)

```
shell/         AppShell, Sidebar, SidebarItem, TopBar, BreadcrumbBar,
               CommandPalette, UserMenu, RoleScopeBadge, DevTopBarTools
layout/        PageHeader, PageSection, RightRail, KpiStrip
data/          DataTable, DataTableColumnHeader, DataTableViewOptions,
               DataTablePagination, FilterBar, DensityToggle, TabularNumber
status/        StatusBadge, DpdBadge, AccountStatusBadge
feedback/      EmptyState, ErrorState, PermissionDeniedState, ContentState,
               Skeletons/{KpiSkeleton,TableSkeleton,CardSkeleton,FormSkeleton}
forms/         FormShell, FormSection, MoneyInput, DatePickerField,
               SelectField, MultiSelectChips, ConfirmDestructiveDialog
pii/           MaskedField, PiiRevealDialog
audit/         AuditTimeline, AuditEventNode, AuditFilterBar
lifecycle/     ActionBar, TransitionConfirmDialog, TransitionDisabledTooltip
documents/     DocumentChecklist, DocumentRow, DocumentPreviewSheet,
               DownloadAllAsZipButton
repayment/     RepaymentScheduleTable, PostRepaymentDialog
disbursement/  DisbursementPanel, DisbursementReadinessChecklist
foreclosure/   ForeclosureQuoteDialog
secrets/       OneTimeSecretCard, TempPasswordCard
misc/          KbdHint, CopyableId, AvatarInitials
```

shadcn primitives generated up front:
```
button input label select textarea checkbox radio-group switch form
card badge table tabs tooltip dialog sheet popover dropdown-menu
navigation-menu avatar breadcrumb skeleton sonner alert command
separator scroll-area calendar hover-card toggle-group progress
```

---

## 9. Data Layer

| Concern | Tool | Config |
|---|---|---|
| Server state (mock) | TanStack Query | staleTime 30s, retry 1, refetchOnFocus off |
| Form state | React Hook Form + Zod | shadcn `Form` wrapper, `zodResolver` |
| Local UI state | useState / useReducer | no Redux, no Zustand |
| Cross-cutting | React Context (split per concern) | Theme, Density, Session, MockScenario |
| URL state | `useUrlFilters<TSchema>(zodSchema)` | round-trips via `useSearchParams` |

Mutations always pass through a typed wrapper that:
1. Generates `Idempotency-Key` (`crypto.randomUUID()`).
2. Calls `mocks/api/*`.
3. On success, invalidates relevant query keys.
4. On error, surfaces typed business-rule errors via `setError`.

**Status transitions and money movements are never optimistic** — audit timing must reflect mock latency.

---

## 10. Mock API Architecture

```
src/mocks/
  db/         state.ts (MockDb), seed.ts, persistence.ts (localStorage v1)
  fixtures/   lsps.ts, products.ts, borrowers.ts, applications.ts,
              schedules.ts, payments.ts, alerts.ts
  api/        index.ts (typed surface mirroring real REST), auth.ts, home.ts,
              loanApplications.ts, borrowers.ts, repayments.ts, disbursements.ts,
              lsps.ts, products.ts, users.ts, apiClients.ts, alerts.ts, reports.ts, audit.ts
  router.ts        URL-pattern → handler dispatch; calls dispatch<T>()
  latency.ts       150–450ms uniform jitter; ?slow=1500ms
  errors.ts        typed business-rule errors (KycIncomplete, DocumentMissing, …)
  idempotency.ts   30s key cache; same-key replay returns cached response
  scenarios.ts     happy | kyc-incomplete | docs-incomplete | schedule-missing |
                   all-overdue | webhook-flaky | permission-denied | server-error | slow-network
```

**Rules (enforced in PR review + lint):**
- Zero `fetch(` outside `src/mocks/`.
- Zero hard-coded fixtures inside components.
- Every component reads from `mocks/api/*` only.
- Idempotency on every mutation.
- Status-changing mutations call `canTransition()` (same as UI).
- Mutations append the relevant audit row; UI's audit-completeness mirrors production (BR-7).
- BR-11 schedule reconciliation runs in the schema (Zod `superRefine`); reused by form + mock ingestion.
- BR-13 partial-payment rejection enforced in `repayments.post()`.
- BR-14 first-payment auto-advance executed inside the same mutation.
- BR-15 full-repayment auto-close + webhook event executed inside the same mutation.
- Persistence: serialized to `localStorage["bhawana-lms-mock-db-v1"]` debounced 200ms.
- Reset / scenario / switch-user controls live in `DevTopBarTools` (DEV only).

---

## 11. Testing Strategy

```
              ┌──────────────────────────┐
              │  Playwright e2e (smoke)  │   1 happy-path per phase
              ├──────────────────────────┤
              │ Integration (RTL+mocks)  │   feature wire-up
              ├──────────────────────────┤
              │ Component unit (RTL+axe) │   composed components
              ├──────────────────────────┤
              │ Pure unit (Vitest)       │   schemas, lifecycle, formatters,
              │                          │   role gates, idempotency, BR-11 calc
              └──────────────────────────┘
```

**Coverage gates (CI-enforced)**

| Layer | Statements | Branches |
|---|---|---|
| `src/lib/`, `src/schemas/` | 95 | 90 |
| `src/mocks/` | 90 | 85 |
| `src/components/app/` | 85 | 75 |
| `src/features/*/components/` | 80 | 70 |
| `src/features/*/pages/` | 70 | 60 |
| **Project minimum** | **80** | **70** |

A11y stack (three layers, all required):
1. `eslint-plugin-jsx-a11y` (zero warnings).
2. `@axe-core/react` mounted in DEV; `console.error` counter test = 0 in production builds.
3. `vitest-axe` assertion in every component test (`expectA11y(container)`).

Plus Lighthouse a11y ≥ 95 on every top-level route, verified once per phase.

---

## 12. Phase Plan

| Phase | Scope | Gate |
|---|---|---|
| **0** | Scaffold: Vite + TS strict + Tailwind v4 + shadcn + Vitest + Playwright + ESLint flat + husky + CI | `npm run verify` green; `npm run build` clean; localhost:5173 boots |
| **1** | Tokens, app shell, mock auth, role guards, role-aware nav, feedback components | All 6 seed roles land correctly; keyboard nav works; coverage ≥ 80% on `lib/` |
| **2** | Mock API + types + lifecycle | Sandbox calls every mock endpoint; failure injection works; BR-11/13/14/15 unit-tested; coverage ≥ 90% on `mocks/` |
| **3** | Composed components (DataTable, StatusBadge, AuditTimeline, MaskedField, etc.) + `/dev/components` sandbox | Lighthouse a11y ≥ 95 on `/dev/components`; coverage ≥ 85% on `components/app/` |
| **4** | Home dashboard | KPIs derive from mock DB; no static decoration; reduced-motion respected |
| **5** | Loan applications list + detail (all 6 tabs, ActionBar, Disbursement, Repayment) | Full lifecycle Playwright spec passes (INITIATED → CLOSED); BR-13 enforced |
| **6** | Borrower 360 + Documents (PII reveal, document audit) | Reveal/preview write to audit; visible in `/audit` |
| **7** | Repayment + Disbursement + Foreclosure (LSP-provided schedule submission with BR-11) | Stale quote rejected; bad-principal-sum surfaces inline; replacement-after-payment blocked |
| **8** | Alerts + Reports + Audit explorer | 5s mock polling flips queued reports; date validation; deep-link from audit event to subject |
| **9** | Admin (LSPs, Products, Users, API clients) | All CRUD flows tested; one-time secret/temp-password reveal flows guarded |
| **10** | Polish, accessibility, responsive QA, **dark mode** | Lighthouse a11y ≥ 95 on every route; dark-mode contrast tests pass; project coverage ≥ 80%/70% |

---

## 13. Definition of Done (per phase)

- ✅ All listed files exist and are exported correctly.
- ✅ `npm run verify` (typecheck + lint + format check + tests + coverage gate) green.
- ✅ `npm run build` clean.
- ✅ `npm run e2e` for the phase's spec passes.
- ✅ Lighthouse a11y ≥ 95 for any new top-level route.
- ✅ Axe runtime: zero violations on touched routes (verified manually in DEV via Chrome DevTools MCP).
- ✅ Manual keyboard-only walkthrough of new pages.
- ✅ Coverage report attached; per-layer minimums met.
- ✅ No console errors, no React 19 warnings, no key warnings.

---

## 14. Hard Don'ts (binding on every agent)

1. **No real network calls.** Zero `fetch(`, `axios`, `XMLHttpRequest` outside `src/mocks/`.
2. **No hard-coded domain data inside components.** Always import from `mocks/api/*`.
3. **No raw `<button>` for actions** — always shadcn `Button`.
4. **No raw `<input>` outside a shadcn `Form`** — RHF + Zod is the only path.
5. **No new dependencies without orchestrator approval.**
6. **No edits to other agents' files.** File-boundaries in agent assignments are binding.
7. **No deviating from the lifecycle map.** Status transitions go through `canTransition()` only.
8. **No partial-installment payments accepted** (BR-13). No optimistic status transitions. No silent failures.
9. **No emoji as icons.** Lucide only.
10. **No animation that doesn't convey meaning.** 150–300ms only. Respect `prefers-reduced-motion`.
11. **PII masked by default.** Reveal flow requires reason and writes to `auditPiiReveal`.
12. **Idempotency key on every mutation.** No exceptions.

---

## 15. When in doubt

- Conflict between BRD and blueprint → prefer BRD (it reflects delivered state).
- Conflict between this plan and BRD on a business rule → escalate to orchestrator. Do not improvise.
- Missing schema field → add to the schema (with comment), then escalate.
- shadcn primitive misbehaves under Tailwind v4 → restyle locally using tokens; document in `components/ui/_NOTES.md`. Do not block.
- Need a new dependency → escalate to orchestrator before installing.

---

*This plan is authoritative. Any agent producing work that contradicts it must escalate before merging.*
