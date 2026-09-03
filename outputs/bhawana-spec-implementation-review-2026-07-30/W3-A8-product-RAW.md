# Wave 3 Agent A8 — Product Catalog / Pricing Audit

**Mode:** Read-only  
**Specs:** `product-catalog-management/spec.md` v1.0.1, `partner-product-mapping/spec.md` v1.0.1, ADR 0004  
**Codebase baseline:** `/Users/siddhant/Desktop/lms` (current HEAD + working tree)  
**Focus question:** Can pricing change affect in-flight loans?

---

## 1. Executive Summary

Bhawana LMS implements a **two-layer product model**: a mutable global `loan_product` catalog row plus immutable `loan_product_version` snapshots. Loan applications and accounts **pin a version at origination/approval**, and all downstream pricing surfaces (EMI schedule, partner API, MIS interest rate, disbursement processing fee) read from that snapshot — not the live catalog row.

**Verdict:** Pricing changes (interest rate, processing-fee rate) **do not retroactively affect in-flight loans** after application creation. Mapping disable / product deactivation blocks **new** originations only; approved pending-disbursal loans can still disburse.

**Overall conformance:** Strong on core bank-grade isolation (FR-013–FR-015, ADR 0004 D5). Gaps are mostly spec-documented prototype limitations (audit summaries, no version browser, no in-use guards) plus a few implementation nuances worth tracking.

---

## 2. Scope & Methodology

| Item | Evidence |
|------|----------|
| Controllers | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LoanProductAdminController.java`, `ProductLspMappingAdminController.java` |
| Service | `ProductConfigurationService.java`, `LoanApplicationOnboardingService.java`, `LspProductCatalogService.java` |
| Entity / migration | `LoanProductVersion.java`, `V104__loan_product_version.sql` |
| Fee model | `LoanFeeCalculator.java`, `DisbursementAmounts.java`, ADR 0004 |
| Freeze / propagation | `LoanApplication.java`, `LoanAccount.java`, `LoanApplicationStatusWriter.java` |
| Tests | `ProductVersioningIntegrationTest.java`, `LoanProductAdminControllerTest.java`, `ProductLspMappingAdminControllerTest.java`, `LoanFeeCalculatorTest.java` |

Traced call paths: admin product update → version insert → intake snapshot → account copy → schedule/disbursement/MIS/webhook reads.

---

## 3. Architecture — Product Terms Flow

```
ADMIN PUT /products/{id}
    → ProductConfigurationService.updateProduct()
    → loan_product row updated (live catalog)
    → IF principal/rate/fee/tenure changed → new loan_product_version row (immutable)

INTAKE POST loan-application
    → findTopByLoanProduct…Desc() → latestVersion
    → LoanApplication(loanProduct, latestVersion, …)  // frozen here

APPROVAL
    → LoanAccount(…, application.getLoanProductVersion(), …)  // same snapshot

SERVICING / DISBURSEMENT / MIS / LSP API
    → loanAccount.getLoanProductVersion().getInterestRate()
    → loanAccount.getLoanProductVersion().getProcessingFeeRate()
```

---

## 4. Version Immutability

| Check | Result | Evidence |
|-------|--------|----------|
| Version rows created on create (v1) and term-changing update | **PASS** | `ProductConfigurationService.createProduct()` L125; `updateProduct()` L181–186 |
| Status/code/name-only update skips new version | **PASS** | `termsDiffer()` L399–414; tested in `ProductVersioningIntegrationTest.updatingInterestRateCreatesVersionTwo_nameOnlyDoesNot` |
| JPA entity has no setters for term fields | **PASS** | `LoanProductVersion.java` — constructor-only population |
| DB unique constraint per (product, version_number) | **PASS** | `V104__loan_product_version.sql` L14 |
| DB-level UPDATE/DELETE prevention | **PARTIAL** | No trigger; immutability is convention + no repository update methods. `ON DELETE CASCADE` on product FK (L3) — moot while spec forbids product delete (FR-009) |
| Admin version list/diff API | **N/A (gap)** | No controller endpoint; matches spec G-2 |

**Finding W3-A8-F05** — Version immutability is application-enforced, not DB-hardened. Acceptable for prototype; production hardening would add `REVOKE UPDATE` or append-only triggers.

---

## 5. In-Flight Loan Pricing Isolation

### 5.1 Snapshot points

| Lifecycle stage | Version pinned? | File / line |
|-----------------|-----------------|-------------|
| Application create | Yes — `latestVersion` at intake | `LoanApplicationOnboardingService.java` L131–134, L212–216 |
| Loan account create | Yes — copied from application | `LoanApplicationStatusWriter.java` L134–139 |
| Repayment schedule | Yes — version interest rate | `LoanRepaymentScheduleService.java` L500–502 |
| LSP read API | Yes — version interest rate | `LspLoanApplicationResponses.java` L44 |
| MIS report | Yes — version interest rate | `AdminReportingService.java` L437 |
| Disbursement fee | Yes — version processing fee rate | `DisbursementAmounts.java` L22–24 |

### 5.2 Integration test proof

`ProductVersioningIntegrationTest.historicalIntegrityAfterProductRateChange`:
- Creates app at 12% rate → approves → changes product to 24%
- EMI unchanged after product edit
- LSP GET still returns `interestRate: 12.00`
- MIS row still shows 12%
- New application gets version 2 at 24%

`ProductVersioningIntegrationTest.disbursementUsesSnapshottedProcessingFeeRate`:
- App approved at fee 2.25% → product fee raised to 5.00% → disburse
- Persisted fee = 1012.50 (= 45000 × 2.25%) — not 2250.00

**Finding W3-A8-F06** — **PASS (bank-grade).** Pricing changes after application creation do not alter schedule, partner disclosure, MIS rate column, or disbursement fee for that loan.

### 5.3 What *can* still change for in-flight loans

| Change | Affects in-flight? | Evidence |
|--------|-------------------|----------|
| Interest / fee rate edit | **No** (after intake) | Above |
| Product `INACTIVE` | **No** at disbursement | `PRODUCT_NOT_ACTIVE` only in `LoanApplicationOnboardingService` L123–128; no re-check at disbursement |
| Mapping `enabled=false` | **No** at disbursement | `PRODUCT_MAPPING_DISABLED` only at intake L147–155 |
| Principal/tenure bounds edit | **No re-validation** at approval/disbursement | Intake validates against live `loanProduct` row L167–183, not version snapshot |

**Finding W3-A8-F11** — Deactivating a product or disabling a mapping blocks new originations only; approved pending-disbursal loans proceed. Matches partner-mapping spec G-6 / EC-005 (intended prototype behavior).

**Finding W3-A8-F02** — Principal/tenure range checks at intake use the **live** `loanProduct` row, not `latestVersion` bounds. At intake they are identical; if bounds are tightened post-intake, an in-flight application outside new bounds is not blocked at disbursement. Rate/fee isolation is stronger than bounds isolation.

---

## 6. LoanProductAdminController

| Spec requirement | Status | Evidence |
|------------------|--------|----------|
| NFR-001 RBAC `SYSTEM_ADMIN`/`PRODUCT_ADMIN` | **PASS** | `@PreAuthorize` L32 |
| FR-001 list/get with mapped LSP projections | **PASS** | `listProducts()` L49–54; `ProductListItemResponse.mappedLsps` L261 |
| FR-002–FR-007 CRUD + validation | **PASS** | `ProductRequest` bean validation L216–231; service `validateRanges` |
| FR-009 no delete | **PASS** | No DELETE mapping |
| FR-011 audit history (25 newest) | **PASS** | `listAuditEvents` L142–146 → `findTop25…` |
| FR-011 mappings on product controller | **PASS** | `GET/PUT /{productId}/mappings` L127–140 |
| Idempotency-Key on create/update | **Enhancement** | L63–75, L96–108 via `AdminApiIdempotencyService` — not in spec |

**Finding W3-A8-F09** — Idempotency support on product mutations is a positive addition beyond spec NFR-002 scope.

**Finding W3-A8-F07** — Admin product list `mappedLsps` includes **all** mapping rows (enabled and disabled) via `listProductViews()` grouping `findAll()` mappings. Spec FR-012 requires projections for admin review; disabled partners still appear as mapped — correct for admin, distinct from partner provisioned list.

---

## 7. ProductLspMappingAdminController

| Spec requirement | Status | Evidence |
|------------------|--------|----------|
| FR-002 grouped + flat entries with `enabled` | **PASS** | `listMappings` L29–37; `listEntries` L39–53 |
| FR-003 replace → all `enabled=true` | **PASS** | `replaceProductMappings` L277–280 |
| FR-004 atomic fail on unknown LSP | **PASS** | L273–275 |
| FR-005 upsert single entry | **PASS** | `upsertEntry` L67–79 |
| FR-006 disabled row retained | **PASS** | `upsertMapping` updates flag, no delete L251–256 |
| NFR-001 RBAC | **PASS** | `@PreAuthorize` L20 |

Replace-via-delete-and-recreate (spec G-2) confirmed at `ProductConfigurationService.replaceProductMappings` L277–281.

---

## 8. Mapping Enable/Disable & Origination Gate

### Enforcement order at intake (`LoanApplicationOnboardingService.doCreateApplication`)

1. `LSP_NOT_ACTIVE` — L114–119  
2. `PRODUCT_NOT_ACTIVE` — L123–128  
3. Load `latestVersion`; `INTEREST_RATE_MISMATCH` if partner sends rate ≠ version rate — L131–136, L274–289  
4. `PRODUCT_NOT_MAPPED` — L138–146  
5. `PRODUCT_MAPPING_DISABLED` — L147–155  

### Partner provisioned catalog

`LspProductCatalogService.listProvisionedProducts` — `findAllByLsp_IdAndEnabledTrue` + LSP ACTIVE + product ACTIVE — L24–28. Matches FR-008.

### Test coverage

| Error code | Automated test |
|------------|----------------|
| `PRODUCT_NOT_MAPPED` | `LspLoanApplicationApiControllerTest`, `LoanApplicationOpsControllerTest` |
| `PRODUCT_MAPPING_DISABLED` | **None found** |
| `PRODUCT_NOT_ACTIVE` | **None found** |
| `LSP_NOT_ACTIVE` | **None found** |

**Finding W3-A8-F03** — Mapping-disable and product/LSP inactive gates are implemented in service code but lack dedicated integration/unit tests (unlike `PRODUCT_NOT_MAPPED`).

---

## 9. D5 / ADR 0004 Fee Model Alignment

| ADR 0004 decision | Implementation | Status |
|-------------------|----------------|--------|
| Model 1 — net cash, gross principal | `DisbursementAmounts.fromLoanAccount` subtracts fee from cash, principal unchanged | **PASS** |
| `LoanFeeCalculator` single source of truth | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/common/money/LoanFeeCalculator.java` | **PASS** |
| Fee from **product** attribute, not per-application input | Rate from `loanProductVersion.getProcessingFeeRate()` | **PASS** (D5) |
| Persist fee on disbursement | `DisbursementOutcomeApplier` L93–96: `principal − adapterNetAmount` | **PASS** |
| MIS reads persisted fee; legacy null → zero | `AdminReportingService.resolveProcessingFeeAmount` L526–531 | **PASS** |
| Webhook `processingFeeAmount` / `netDisbursedAmount` | `LoanWebhookPayloads.disbursement` L85–92 | **PASS** |
| Min-net guard | `DisbursementAmounts` L27–32; `DisbursementPreflightValidator` L134–138 | **PASS** |
| GST out of scope | Not implemented | **PASS** (ADR + spec G-4) |

**Finding W3-A8-F12** — **PASS.** Fee computation routes through `LoanFeeCalculator` on initiation, preflight, and webhook fallback. MIS uses persisted amount post-disbursement per ADR.

**Finding W3-A8-F08** — Fee **persistence** in `DisbursementOutcomeApplier` derives fee as `principal − requestLog.amount` (actual cash delta), not a direct `LoanFeeCalculator` call. Initiation path uses calculator-derived net amount, so they should match; divergence would only occur if adapter amount were overridden. Low drift risk but two formulas exist.

---

## 10. Product Change Audit (D9)

| Event | Written? | Evidence |
|-------|----------|----------|
| `PRODUCT_CREATED` | Yes | `createProduct` L126–130 |
| `PRODUCT_UPDATED` | Yes | `updateProduct` L188–197 |
| `PRODUCT_MAPPINGS_REPLACED` | Yes | L286–290 |
| `PRODUCT_MAPPING_ENTRY_UPDATED` | Yes | L257–261 |
| Actor + correlation ID | Yes | `recordAuditEvent` L347–354 |
| Structured before/after diffs | **No** | Summary string only — spec G-1 |

**Finding W3-A8-F01** — **`PRODUCT_UPDATED` audit summary omits `interestRate` and `processingFeeRate`.** Summary at `ProductConfigurationService` L191–196 includes status, principal range, tenure — but not rate or fee. A pricing-only change produces an audit event that does not disclose the pricing change in human-readable form. Version rows capture the truth machine-side, but D9 human audit trail is incomplete for pricing edits.

---

## 11. Database Schema — V104

File: `/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V104__loan_product_version.sql`

| Element | Detail |
|---------|--------|
| Table | `loan_product_version` with all term columns + `effective_from`, `created_by` |
| Backfill | All existing products → version 1 from current `loan_product` row L17–42 |
| FK on `loan_application` | `loan_product_version_id NOT NULL` L44–54 |
| FK on `loan_account` | `loan_product_version_id NOT NULL` L59–69 |
| Tenant read grant | L74–77 |

**Finding W3-A8-F04** — V104 backfill assigns **version 1 from product state at migration time** to all historical applications/accounts. Loans originated before versioning, after prior catalog edits, may have incorrect historical term snapshots. Forward-only from V104 deployment; no retroactive correction path.

---

## 12. Test Evidence Matrix

| Test class | What it proves |
|------------|----------------|
| `ProductVersioningIntegrationTest` | Version create on product create/update; name-only no version; **full in-flight pricing isolation** (EMI, API, MIS, fee at disbursement) |
| `LoanProductAdminControllerTest` | CRUD, validation, RBAC, audit events, mapping replace |
| `ProductLspMappingAdminControllerTest` | Upsert, list entries, audit on mapping change, no duplicate rows |
| `LoanFeeCalculatorTest` | Calculator rounding, bounds, null guards |
| `LoanDisbursementCommandServiceProcessingFeeTest` | Disbursement initiation uses net-of-fee amount |
| `LoanWebhookPayloadsDisbursementTest` | Webhook fee fields from version rate |

**Gap:** No test asserting in-flight loan survives product `INACTIVE` or mapping disable through to disbursement (behavior is implicit from missing re-checks).

---

## 13. Spec Gap Register (Code vs Spec "Current vs Target")

| Spec gap | Observed in code | Finding |
|----------|------------------|---------|
| G-1 Summary-only audit | Rate/fee omitted from `PRODUCT_UPDATED` summary | W3-A8-F01 |
| G-2 No version browser UI/API | No version list endpoint | W3-A8-F10 (informational) |
| G-3 No status transition guards / in-use protection | Any status set; deactivate with active loans OK | W3-A8-F11 |
| G-4 No GST on fee | Not implemented | Aligned |
| G-5 No per-partner rate overrides | Mapping is enable-only | Aligned |
| G-6 Audit pagination (25 cap) | `findTop25…` | Aligned |
| Partner G-1 Replace can't disable | Upsert required for disable | Aligned |
| Partner G-6 In-flight unaffected by disable | No disbursement re-gate | W3-A8-F11 |

---

## 14. Findings Register

| ID | Severity | Finding | Recommendation |
|----|----------|---------|----------------|
| **W3-A8-F01** | Medium | `PRODUCT_UPDATED` audit summary excludes interest rate and processing fee rate | Include rates in summary or add structured diff JSON |
| **W3-A8-F02** | Low | Intake bounds checks use live `loanProduct`, not version snapshot; no re-check at disbursement | Validate against snapshotted version bounds at intake; optional disbursement guard |
| **W3-A8-F03** | Low | No tests for `PRODUCT_MAPPING_DISABLED`, `PRODUCT_NOT_ACTIVE`, `LSP_NOT_ACTIVE` | Add integration tests mirroring `PRODUCT_NOT_MAPPED` |
| **W3-A8-F04** | Low | V104 backfill cannot reconstruct pre-migration term history | Document limitation; accept or plan historical correction ADR |
| **W3-A8-F05** | Info | Version table immutability is app-level only | Consider DB append-only policy for production |
| **W3-A8-F06** | **Pass** | Pricing isolation for in-flight loans proven end-to-end | Maintain `ProductVersioningIntegrationTest` in CI |
| **W3-A8-F07** | Info | Admin list shows disabled mappings in `mappedLsps` | Document; or add `enabled` flag to projection |
| **W3-A8-F08** | Low | Fee persistence uses cash delta, not direct calculator call | Add assertion test: persisted fee == `LoanFeeCalculator(principal, version.rate)` |
| **W3-A8-F09** | Info | Idempotency-Key on product mutations (beyond spec) | Keep; document in API spec |
| **W3-A8-F10** | Info | No version history admin API | Spec G-2; defer or add read-only endpoint |
| **W3-A8-F11** | Info | Product deactivation / mapping disable does not block in-flight disbursement | Intentional per spec; confirm with product/legal |
| **W3-A8-F12** | **Pass** | ADR 0004 / D5 fee model aligned via `LoanFeeCalculator` + version snapshot | No action |

---

## 15. Conclusion

### Can pricing change affect in-flight loans?

**No — for interest rate and processing fee rate**, after application intake. The system implements spec FR-013–FR-015 and ADR 0004 Model 1 correctly:

- Version snapshots are created on term-changing product edits and are **never updated in application code**.
- Applications freeze `loan_product_version_id` at create; accounts inherit it at approval.
- Schedule, partner API, MIS, disbursement preflight, and webhooks all read **snapshotted** rates.
- `ProductVersioningIntegrationTest` provides bank-grade regression evidence.

**Partial isolation for principal/tenure bounds** — validated only at intake against the live catalog row; not re-enforced at disbursement.

**Mapping/product lifecycle changes** affect **new** originations only; in-flight approved loans are not re-gated — consistent with partner-mapping spec assumptions.

### Priority actions (if hardening beyond prototype)

1. **W3-A8-F01** — Enrich audit summaries for pricing changes (highest audit/compliance value).  
2. **W3-A8-F03** — Close test gaps on disable/inactive gates.  
3. **W3-A8-F02** — Align bounds validation to version snapshot at intake.

No blocking defects found for core pricing immutability or D5/ADR 0004 fee alignment.

[REDACTED]