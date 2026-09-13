# ADR 0008 — Tiered quality gates and executable invariants

- **Status:** Accepted (2026-09-12)
- **Source:** Audit corpus in `docs/audits/` (66 findings: 7 critical, 32 high, 22 medium, 5 low); finding M17 (local gates fail, journeys not exercised in CI)
- **Related:** ADR 0005 (tenant scope fail-closed), ADR 0006 (schema drift), `scripts/quality/`, `scripts/contract-drift/`, `.github/workflows/`

## Context

The audits catalogue 66 findings. Classifying them by defect class rather than by severity gives:

| Class | Approx. count |
|---|---|
| Concurrency, atomicity, TOCTOU | 13 |
| Contract truthfulness (API/UI claims ≠ behaviour) | 10 |
| Trust boundary, auth, identity | 10 |
| Financial invariants | 9 |
| Transaction boundary vs. external effect | 5 |
| Unbounded resources, no deadline | 6 |
| Observability and audit gaps | 6 |
| Environment and drift | 7 |

**Roughly three of the sixty-six were reachable by a type checker, a linter or a formatter**, and those three needed custom rules rather than a stock configuration. The defects are not syntactic. They are invariant violations: wrong only under concurrency, only across a transaction boundary, or only when two artifacts that are supposed to describe one thing quietly stop agreeing.

Expanding the existing gates along the axis they already occupy — stricter `tsc`, more ESLint rules, Prettier on more file types — therefore has a low ceiling. It cannot reach the defect population that actually causes incidents here.

At the same time the gates that did exist had holes that were cheap to close:

- The JaCoCo line floor was `0.35` against a measured `87.04%`, a 52-point silent regression budget, with no branch rule at all.
- `OpenApiContractExportTest#exportOpenApiSnapshot` is `@EnabledIfSystemProperty`, and CI ran a plain `mvnw verify`. Nothing forced backend controllers, `openapi/openapi.json` and the committed frontend types to agree.
- `backend-ci.yml` path filters omitted the root `pom.xml`, so a parent-version or Java-level change triggered no build.
- The backend had no static analysis of any kind, while the frontend had the stricter gate.
- There was no supply-chain or secret-scanning gate in a system holding PAN, Aadhaar and bank account numbers.
- Real browser session-transport tests (H22) and the existing journey specs were run by hand during review and then never wired up.

## Decision

Gates are organised in tiers, ordered so that the cheapest signal fails first.

**Tier 0 — Hygiene.** Frontend `verify` runs typecheck, lint, format, encoding, unit tests with a coverage ratchet, build and bundle boundaries.

**Tier 1 — Executable invariants.** ArchUnit fitness functions in `backend/src/test/java/com/bhawana/lms/architecture/`. Each rule encodes a finding the audits actually produced, and each exception is an allowlist entry with a written justification — never a package-level exemption.

| Rule | Encodes |
|---|---|
| `ClientIpResolutionArchitectureTest` | H03 — canonical client IP is the only client IP |
| `CredentialRandomnessArchitectureTest` | H04 — no predictable randomness in production code |
| `ScheduledJobConcurrencyArchitectureTest` | H12 — every scheduled job declares a concurrency strategy |
| `TransactionalExternalEffectArchitectureTest` | H24 — a database transaction is not an external transaction |
| `LspTenantElevationArchitectureTest` | ADR 0005 — LSP surface does not widen to admin scope |
| `ControllerArchitectureTest`, `ServiceLayeringArchitectureTest`, `LazyInjectionArchitectureTest`, `LspSurfaceArchitectureTest` | pre-existing layering guarantees |

**Tier 2 — Contract drift.** `scripts/contract-drift/check-openapi-drift.sh` regenerates the OpenAPI document from the live backend context and the frontend types from that document, failing on any diff. Deterministic, no network, no flake. `scripts/schema-diff/check-reference.sh` (ADR 0006) continues to cover the database contract.

**Tier 3 — Tests and coverage ratchet.** `mvnw verify`, carrying the PostgreSQL overlapping-transaction tests. JaCoCo becomes a ratchet at 85% line / 64% branch, against a measured 86.63% / 66.35%. Raise when coverage rises; never lower to make a build pass.

**Tier 4 — Journey E2E (soaking).** `journey-e2e.yml` runs canary, loan-lifecycle and responsive journeys against a real backend, Postgres, Redis and MailHog (finding M17). It reports failures but is temporarily non-blocking because the dormant demo seeder has drifted from the durable-intent disbursement workflow and cannot yet stage all required states deterministically. A missing backend still fails the job visibly; it does not turn into a green skipped run.

**Tier 5 — Static security and supply chain.** CodeQL for Java/Kotlin and JavaScript/TypeScript with extended security and quality queries; Dependabot for Maven, npm and Actions; `npm audit` failing on high and critical; GitHub dependency review across Maven/npm changes; gitleaks over full history; immutable commit pins for workflow actions.

**Enforcement points.** A `pre-push` hook runs the fast tier (Tiers 0–2, scoped to changed paths) via `scripts/quality/gate.sh`; GitHub Actions runs every tier. A `main` ruleset requires pull requests and every blocking check, because a workflow triggered after a direct push cannot prevent that push from landing.

## Consequences

- New violations of the encoded invariants fail in about thirty seconds with a named class and method, instead of surviving to an audit months later.
- Known debt is carried in allowlists with written reasons rather than hidden by a weakened rule. The allowlists are themselves tested: an entry naming a method that no longer exists fails the build, so the list cannot rot into decoration.
- Adding a scheduled job or a `@Transactional` method that reaches storage now requires an explicit decision, recorded in code.
- The three API artifacts cannot silently diverge.
- Cost: the ratchet must be raised deliberately as coverage rises, and every new fitness-function exception costs a written justification. Both are intended.
- Tier 4 reports while its fixtures soak and becomes blocking only after cold-start seeding is
  deterministic. Environment-dependent phase-8 cases stay outside the subset; silently skipped
  journeys must never count as a passing gate.

## Limits

- These rules cover the trust-boundary, transaction-boundary, scheduling and contract classes. They do **not** cover the financial-invariant class (9 findings) or most of the concurrency class, which need property-based and overlapping-transaction tests rather than static rules. Tier 1 is a floor, not a ceiling.
- Error Prone and NullAway were evaluated and deferred: on a codebase this size they produce a large first-run backlog, which is a cleanup project rather than a gate that can land in one change.

## Amendments

| Date | Change |
|---|---|
| 2026-09-12 | Initial acceptance; Tiers 0–5; H03 call-site repair in `BorrowerAdminController` and `LspBorrowerApiController` |
