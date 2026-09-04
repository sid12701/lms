# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

**Primary — internal operations staff.** Confirmed as the audience design optimizes for first when internal and partner needs conflict. Three roles, all signing in to the same shell:

- `OPS_USER` — works loan applications, borrowers, and operational alerts as their day. The highest-frequency, highest-context user of the system.
- `SYSTEM_ADMIN` — the above plus tenant (LSP) registry, user administration, API-client credentials, portfolio MIS/reports, and the audit explorer.
- `PRODUCT_ADMIN` — loan product configuration.

**Secondary — LSP partner staff** (`LSP_UI_READ`, `LSP_UI_WRITE`). External users of the lending service providers, confined to `/my-loans` and `/my-loans/:id`. Their surface is deliberately read-mostly: triage, document attachment, invalidation, and disbursement bank verification. They land on `/my-loans`; internal routes reject them.

**Not users of this UI: borrowers.** They never sign in. They exist in the system as records that internal staff and partner staff read; the platform reaches them through disbursement and repayment, not through a screen.

## Product Purpose

Bhawana LMS is a multi-tenant loan management system. Lending Service Providers originate loans through the API; an internal rule engine approves them; the platform disburses funds to borrowers and collects repayment, operating each LSP's own bank accounts through a bank integration.

The web UI is not where lending happens — it is where lending is **supervised**. It exists so internal staff can see the state of every application and loan account, catch the ones that need a human, and act with confidence on money that is already in motion. Success is an ops team that can tell, at a glance, which loans are healthy, which are in flight, and which are stuck — and that never takes an action the money state makes unsafe.

## Positioning

Three commitments a neighbouring LMS could not truthfully copy:

1. **Origination is API-only, by design** (ADR 0003). `LSP_UI_WRITE` does not grant loan creation, and no create form exists in the browser. The LSP's own onboarding system — KYC, bureau pulls, employer verification — submits applications machine-to-machine. Re-typing 35 fields of PAN, Aadhaar, bank, employment, and address data into a browser is duplicate data entry, not a workflow, and it would be a phishing target. There is no manual fallback.
2. **The rule engine is the trusted approver.** Human approval gates were deliberately removed from the lifecycle. The system's stance is "the engine decided and the decision is auditable," not "a human typed it; trust the human."
3. **Per-LSP bank accounts, never a shared pool.** Each LSP operates its own disbursal and collection account pair at the bank. The platform always acts on the accounts belonging to the LSP that owns the loan.

## Operating Context

**Stage: pre-launch build-out.** No external users on the platform yet. Interaction and visual patterns can still change before habits form — this is the widest latitude the product will ever have.

**Environment: desktop only.** Office laptops and monitors. The existing app shell already reflects this: its two-column grid engages at the `lg` breakpoint and stacks below it. Small screens remain a graceful fallback, not a designed target.

Internal surfaces in the shell: Home, Loan applications (+ detail), Borrowers (+ detail), Alerts, Reports/MIS, LSPs, Loan products, Users, API clients, Audit. Partner surface: My loans (+ detail). Public: Login, forced Change password.

The work is dense and record-oriented: filtered tables of applications and accounts, drill-down detail pages, operational alert queues, portfolio reporting, and an append-only audit trail. Money is Indian rupees; borrower documents are Indian KYC instruments (PAN, Aadhaar, address proof, income proof, bank statement, photograph, KFS, loan agreement).

## Capabilities and Constraints

**Loan application lifecycle** (canonical, mirrors the backend enum): `INITIALIZED`, `AWAITING_APPROVAL`, `APPROVED_PENDING_DISBURSAL`, `REJECTED`, `DISBURSEMENT_RETRY`, `INVALID`, `DISBURSED`, `UNDER_REPAYMENT`, `CLOSED`, `FORECLOSED`. Unknown values arriving from the API are surfaced as `Unknown (<raw>)` — status drift is made visible, never silently folded into a known state.

**The point of no return — the system's hardest constraint.** A disbursement decomposes into a debit leg (funds leave the LSP's disbursal account) and a credit leg (funds land with the borrower), settling independently. Once the debit leg succeeds, the disbursement must never be re-initiated; a second initiation would debit the LSP twice. The only forward path is reconciliation — polling the bank's status checks until the money is confirmed either credited to the borrower or returned to the LSP's disbursal account.

**In flight means hands-off.** While a disbursement attempt is initiated but unresolved, the money may already have left the LSP's account. No manual status changes, no second initiation. Leg-level detail belongs on the attempt; the account itself simply reads as awaiting its verdict. Any UI that makes an in-flight loan look actionable is a defect, not a design choice.

**Evidence versus state.** The disbursement log is append-only, one immutable row per bank call. It is evidence, never state, and is never overwritten.

**Terminology to hold** (from `CONTEXT.md`, with the terms it explicitly rejects):

| Use | Never |
|---|---|
| disbursal account | pool account, source account, lender account |
| collection account | receivables account, repayment account |
| disbursement | payout, transfer, payment |
| disbursement attempt | retry, disbursement request |
| disbursement log | disbursement record, request log |
| debit leg | withdrawal, source debit |
| credit leg | payout, beneficiary transfer |

**Security constraints that touch the UI:** the browser holds the access JWT **in memory only**; `localStorage` may keep session metadata (user, roles, expiry) for shell continuity, and reload acquires a fresh token via an HttpOnly refresh cookie. Frontend HTTP clients refuse credential-bearing cross-origin absolute URLs. Users on a temporary password are forced through `/change-password` before any protected route.

**Undecided / deliberately open:** several money-isolation and borrower-relationship items are deferred rather than resolved (`docs/deferred-implementation.md`). Future work must not present deferred behaviour as shipped.

## Brand Commitments

Committed in code at `frontend/src/lib/product-branding.ts`:

- `PRODUCT_ORGANIZATION_NAME = "Bhawana Capital"`
- `PRODUCT_TAGLINE = "Sovereign Ledger"`

The top bar renders "Bhawana". "Bhawana" is also user-facing product vocabulary inside the domain — "Bhawana loan ID" is a column header and filter label, distinct from the LSP's own external loan id. No logo or brand kit has been established as binding.

## Evidence on Hand

Real, in-repo:

- `CONTEXT.md` — domain language, the disbursement model, and security properties, written as a working glossary with an example dialogue between dev and domain expert.
- `docs/adr/0001`–`0006` — accepted architecture decisions, including the API-only origination stance.
- `docs/` — architecture package and blueprint, API references, runbooks, the partner loan event feed guide, and the backend documentation.
- A frontend test suite with axe assertions across ~90 files and a WCAG contrast test pinned to literal token values.

**Absences future work must not fabricate:** no customer testimonials, no named live LSP partners, no production volume or performance benchmarks, no pricing or licensing claims, no uptime or deployment record. The platform has no external users yet.

**Removed as stale (2026-08-01):** `docs/UI pages.md` and its `frontend/docs/Frontend/` copy described `src/router.tsx`, an `lsp-loans/` feature, a `/dashboard` route, and framer-motion — none of which exist. Deleted rather than carried forward, and the schema files that cited it now point at live sources instead. **The route table in `frontend/src/routes/router.tsx` is the only authority on what surfaces exist and who may reach them.**

## Product Principles

1. **The money state governs what the interface offers.** Affordances follow reconcilability, not tidiness. If an action is unsafe at the current point in the disbursement, it must not be presented as available — and the reason must be legible, not merely enforced.
2. **Show what the system decided, and why.** The rule engine replaced human approval gates, so the UI's job is to make an automated decision inspectable and trustworthy. An outcome without its basis is a support ticket waiting to happen.
3. **Evidence is never overwritten.** Append-only records — audit trail, disbursement log — are presented as history, never as editable state.
4. **Design for the practised operator.** The primary user is in this tool all day, on a desktop, working dense records. Density, scanability, keyboard efficiency, and consistency outrank expression. Brand lives in precise details, not in decoration.
5. **Make drift visible.** Unknown statuses, deferred capabilities, and unverified data surface as themselves rather than being silently normalised into something that looks complete.

## Accessibility & Inclusion

WCAG 2.1 AA is the operating standard, already enforced in the test suite rather than aspired to:

- axe assertions across roughly 90 test files.
- `src/styles/dark-contrast.test.ts` pins dark-mode token hex values against AA thresholds (4.5:1 normal text, 3:1 large text and non-text UI), so a palette edit that breaks contrast breaks the build.
- **Status is never communicated by colour alone** — semantic intents pair colour with icon and text (`WCAG 1.4.1`), an explicit rule in both the token file and component code.
- Nested-interactive violations are treated as real defects, with existing components restructured to avoid them.

Both light and dark themes are first-class and must stay AA-compliant.
