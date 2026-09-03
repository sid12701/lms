# W1 — Platform Foundations (Verified)

**Status**: Verified (lead agent)  
**Date**: 2026-07-30  
**Agents**: [A1 Schema](b6015806-30c1-4e57-99a2-6005b1a68131), [A2 Tenancy](5c054b6e-f037-443c-b2bb-df84cb2b69c6), [A3 Idempotency](0e6ee94b-95f0-481c-9c0b-36f51c8797eb), [A22 Challenger](32ee5fa5-2f0e-4898-9f8e-237e3bd173cf)  
**Baseline**: LMS `bfd571f` + dirty worktree (not material to W1)  
**Spec anchors**: `platform-setup/database-schema/spec.md`, ADR 0005, D8, `CONTEXT.md`

---

## 1. Executive assessment

Platform foundations show **deliberate bank-aware design**, not framework defaults: dual admin/tenant datasources, Postgres RLS on core loan tables, fail-closed unscoped access (ADR 0005), lease-fenced API idempotency, and `NUMERIC(19,2)` money. Gaps are real but mostly **defense-in-depth and money-path concurrency**, not “no tenancy.”

Under the approved **pre-production / mock-rails** posture, W1 has **no surviving Critical**. Strongest verified issues are **High**: foreclosure settlement payments with null idempotency keys (raceable), and optional HTTP idempotency on ops money endpoints that rely on partial domain uniqueness.

---

## 2. Purpose and actors

Secure multi-tenant data access (LSP vs Bhawana admin), durable request replay safety for partner/ops APIs, and schema integrity for loan/money tables. Actors: LSP API clients (tenant scope), internal admins (admin scope), scheduled workers (explicit `runAsAdmin`).

---

## 3. End-to-end architecture

```
Request → JWT filter → AuthenticationTenantScopeFilter (principal → TENANT|ADMIN)
      → (LSP) TenantAwareDataSource SET LOCAL app.current_lsp_id + tenant DB role → RLS
      → (Admin/workers) admin DataSource (table owner; RLS not applied)
Money/idempotent writes often elevate to admin via AdminScopedTransactionExecutor /
LspApiIdempotencyService.runUnderAdminScope, then filter by lspId in application code.
```

Documented rationale: ADR 0005, V45 comments (ops_alert admin-only), V41 RLS + tenant role grants.

---

## 4. Traceability (foundations)

| Requirement | Spec/ADR | Backend | DB | Tests | Status |
|---|---|---|---|---|---|
| D8 tenant isolation | README D8, ADR 0005 | `tenant/*`, filters | V41+ RLS (23 tables) | `TenantIsolationPostgresIntegrationTest` | Complete (hybrid) |
| Fail-closed unscoped | ADR 0005 | `TenantRoutingDataSource` | — | ADR-cited #89/#182 | Complete |
| Admin escape hatch | none dedicated | `AdminScopedTransactionExecutor` | bypasses RLS | workers/onboarding | Complete (undocumented as product control) |
| API idempotency leases | piecemeal specs | `Idempotency*` | V40, V103, V112 | lease/crash/race tests | Partial |
| Payment idempotency | servicing specs | `LoanRepaymentCommandService` | V56, V92 | Issue86 | Partial |
| Schema as-is doc | `database-schema/spec.md` | — | Flyway→V113 | SchemaCheckConstraints* | Complete (as-is) |

---

## 5–12. Reviews (condensed)

**Backend / tenancy:** Hybrid DB+app isolation is intentional. LSP HTTP holds tenant scope; many write paths elevate to admin (idempotency coordinator, onboarding D3). Workers fail closed if unscoped. Non-Postgres JDBC URL collapses tenant pool to admin (`TenantIsolationDataSourceConfig` 35–37) — fail-soft for local/non-PG.

**Database:** Money columns `NUMERIC(19,2)`. Payment amount CHECK `>= 0` (allows zero). No FORCE RLS (table owner/admin bypass expected). `disbursement_intent` has no RLS and no tenant GRANT (admin-only — appropriate). Tenant role has DELETE on payment/audit tables (unused by HTTP). No DB immutability triggers on financial history. Dual `borrower_lsp_access` + `borrower_lsp_relationship` (S19 residual).

**Idempotency:** Lease claim/reclaim/fencing is sound. Fingerprint mismatch → 409. Action+complete in same admin txn. Crash recovery reconstructor exists **only** for `LOAN_APPLICATION_CREATE`. Payment path uses `synchronized(key.intern())` (single-JVM) plus DB unique key. Retention can purge pending rows.

**Frontend:** Out of scope for W1 (guards ≠ authorization).

**Security / multi-tenancy:** Isolation is DB-enforced for tenant role; admin path is trust-the-application. Compromised admin credential or buggy admin-scoped write without `lspId` filter is the residual risk class.

**Financial correctness (foundation layer):** No ledger. Mutable account/installment state. Intent uniqueness for live disbursements is good foundation for W5.

**Ops resilience:** Compose-only deploy; no W1 DR evidence (deferred to synthesis).

**Testing:** Strong tenant isolation + idempotency race/lease tests; weak coverage for `IDEMPOTENCY_RECOVERY_REQUIRED` / in-progress waits; foreclosure concurrency under-tested.

---

## 13. External research (applicability, not legal advice)

| Source | Supports | Relevance |
|---|---|---|
| OWASP ASVS V4 Access Control | Fail-closed authz, least privilege DB roles | Supports ADR 0005; DELETE grants on audit tables conflict with least privilege |
| PostgreSQL docs — RLS | Table owners bypass RLS unless FORCE | Explains admin DS design; documents residual |
| RBI Digital Lending Directions (assumed India) | RE accountability for LSP arrangements; auditability | Undocumented admin escape hatch + erasable audit via DELETE grant are governance gaps |

---

## 14. Default-driven decision register

| Decision | Classification | Notes |
|---|---|---|
| Dual DS + RLS | Documented + demonstrated | ADR 0005; V41/V45 |
| Admin elevation on LSP idempotent writes | Demonstrated; rationale inferred (D3/idempotency durability) | Latent cross-tenant write risk if filters slip |
| Optional Spring `Idempotency-Key` header | Mixed | Payment service requires key; ops disbursement/FC optional |
| Magic pending JSON in `response_body` | Convenience | Works; fragile vs typed state column |
| AMQP dependency unused | Default-driven residue | `spring-boot-starter-amqp` with 0 usages |

---

## 15. Verified findings

### W1-F01 — Foreclosure settlement payment has null idempotency_key (raceable)
- **Severity**: High · **Confidence**: High  
- **Discovered**: A22 challenger · **Verification**: Lead confirmed  
- **Evidence**: `LoanForeclosureCommandService.java` 249–261 (payment saved with `null` idempotency); quote ACTIVE check 228–234 without row lock / version fence before insert; ops execute key optional (`LoanApplicationOpsController` 584–588); LSP path requires key via `lspApiIdempotencyService` but still posts null payment key inside.execute  
- **Why it matters**: Concurrent execute (or crash between payment insert and quote.execute) can double-post foreclosure settlement payments; payment unique constraint does not apply to NULL keys (Postgres UNIQUE allows multiple NULLs).  
- **Scenario**: Two admin clients omit Idempotency-Key and POST execute concurrently on same ACTIVE quote.  
- **Root cause**: Foreclosure bypasses payment idempotency model; relies on quote status alone without locking.  
- **Default-driven?**: Partially — convenience vs shared payment claim path.  
- **Bank expectation**: Settlement posting idempotent and concurrency-safe under row/lease lock.  
- **Change**: Claim payment with UUID key (or quoteId-derived key); `SELECT … FOR UPDATE` on quote; unique partial index preventing two RECEIVED foreclosure settlements per account/quote.  
- **Tests**: Concurrent dual-execute integration test; restart mid-execute.

### W1-F02 — Ops money endpoints treat Idempotency-Key as optional
- **Severity**: Medium · **Confidence**: High  
- **Agents**: A3 (High) → challenger/lead **downgrade**  
- **Evidence**: `LoanApplicationOpsController` 448–451 (disbursement), 584–588 (foreclosure execute); contrast payment `requireIdempotencyKey` (`LoanServicingSupportService` 113–116)  
- **Mitigations present**: `uk_disbursement_intent_live_account`; disbursement status gates  
- **Why Medium not High**: Domain uniqueness reduces double-disburse; still allows duplicate side effects / confused ops retries without keys  
- **Change**: Require UUID v4 keys on all money-mutating ops endpoints; keep domain uniqueness as second fence.

### W1-F03 — Idempotency crash recovery only for loan create
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: only `LoanApplicationCreateIdempotencyReconstructor`; coordinator throws `IDEMPOTENCY_RECOVERY_REQUIRED` when reclaiming unsupported ops (`IdempotencyExecutionCoordinator` 333–343, 386–396)  
- **Impact**: Partner/ops retry after lease expiry may hard-fail even if domain state succeeded  
- **Change**: Reconstructors for foreclosure execute, disbursement initiate, status transition; or store response before external effects.

### W1-F04 — Tenant DB role DELETE on financial/audit tables
- **Severity**: Medium · **Confidence**: High  
- **Agents**: A1 Critical → challenger/lead **downgrade**  
- **Evidence**: V41 grants 209–213; no HTTP DELETE on those entities  
- **Change**: Revoke DELETE on append-only tables from tenant role; use soft-cancel or admin-only deletion.

### W1-F05 — No DB immutability for posted money/audit history
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: UPDATE/DELETE grants; mutable `loan_account` / installments; no revoke/trigger immutability  
- **Change**: Append-only payment/disbursement log privileges; corrections via reversal entries (ties to W5/W8 ledger).

### W1-F06 — Payment `reference` not unique; installment lock is the real fence
- **Severity**: Low · **Confidence**: High  
- **Agents**: A1 High → **downgrade**  
- **Evidence**: no UK on `reference`; `resolveTargetInstallmentForUpdate` + exact amount check prevents double EMI with different keys  
- **Note**: Still weak for external reconciling by bank reference.

### W1-F07 — LSP idempotent writes elevate to admin datasource
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: `LspApiIdempotencyService` 37–44; coordinator `adminScopedTransactionExecutor.call`  
- **Change**: Prefer tenant-scoped execution for tenant-owned rows; keep admin only for true cross-tenant reads (D3). Architecture test for new admin elevations.

### W1-F08 — Non-Postgres URL collapses tenant DS to admin
- **Severity**: Low (Medium if non-PG ever used “prod-like”) · **Confidence**: High  
- **Evidence**: `TenantIsolationDataSourceConfig` 35–37  

### W1-F09 — Idempotency retention can delete pending leases
- **Severity**: Medium · **Confidence**: High  
- **Evidence**: A3 report + retention worker (verify purge predicate before prod)  
- **Change**: Never purge `PENDING` rows; quarantine instead.

### W1-F10 — `disbursement_intent` admin-only without RLS
- **Severity**: Observation · **Confidence**: High  
- **Evidence**: V111 no RLS/GRANT — **appropriate** given admin-only money-out; document as pattern  

### Positive controls (retain)
- ADR 0005 fail-closed scope  
- RLS on core loan tables + SET LOCAL GUC  
- Lease fencing + fingerprint conflict  
- `NUMERIC(19,2)` money; intent `tran_ref_no` uniqueness  

---

## 16. Target architecture (W1 slice)

1. Least-privilege tenant grants (no DELETE on audit/money history).  
2. Money mutations: required idempotency + domain unique constraints + row locks.  
3. Admin elevation allowlist + static analysis.  
4. Typed idempotency state column; reconstructors per money op.  
5. Document tenancy/idempotency as first-class platform specs (currently unowned).

---

## 17. Remediation sequence (W1)

| Priority | Item | Effort | Depends |
|---|---|---|---|
| Immediate | Lock foreclosure execute; non-null payment idempotency key | S | — |
| 30d | Require ops Idempotency-Key; revoke tenant DELETE on audits | S–M | — |
| 30d | Reconstructors for money ops; retention skip pending | M | — |
| 60–90d | Immutability / reversal model; tenancy platform spec | L | W5 ledger |

---

## 18. Open questions

- Will production ever use non-Postgres JDBC?  
- Accept admin elevation for all LSP writes, or only D3 paths?  
- Target retention for idempotency bodies vs DPDP (PII in response_body)?

---

## 19. Evidence index

- Raw agent outputs: `W1-A1-schema-RAW.md`, `W1-A2-tenancy-RAW.md`, `W1-A3-idempotency-RAW.md`, `W1-A22-challenge-RAW.md`  
- Lead inspections: V41, V45, V92, V111, V112; `AdminScopedTransactionExecutor`; `IdempotencyExecutionCoordinator`; `LoanRepaymentCommandService`; `LoanForeclosureCommandService`; ADR 0005  

## 20. Subagent coverage / verification record

| Finding | Agent | Lead | Result |
|---|---|---|---|
| A1-F01 Critical DELETE | A1 | +A22 | Downgraded → W1-F04 Medium |
| A1-F04/F05 payment UK | A1 | +A22 | Downgraded → W1-F06 Low |
| A3-F01 optional keys | A3 | +A22 | Downgraded → W1-F02 Medium |
| A3-F02 recovery gap | A3 | +A22 | Downgraded → W1-F03 Medium |
| Foreclosure null key | A22 | Lead | **Promoted** → W1-F01 High |
| A2-F09 admin elevation | A2 | Lead | Confirmed → W1-F07 Medium |
