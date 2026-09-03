# W3 — Tenant & Product Configuration (Verified)

**Status**: Verified  
**Agents**: [A7 LSP](1cdbc0a4-3723-46d1-bf82-fded695f98e0), [A8 Product](7959f76f-8190-4682-a31f-0d8612a18947)  
**Specs**: partner-lsp-onboarding, product-catalog, partner-product-mapping · ADR 0002, 0004 · D4, D5

---

## 1. Executive assessment

LSP disable **kill chain is deliberately implemented and tested** (ADR 0002): token versions bumped, API clients deactivated, principal cache evicted on the handling node, audit + alert. Product **versioning isolates in-flight loan pricing** (strong bank-grade control; ProductVersioningIntegrationTest). Residual risks are multi-replica cache staleness (≤30s, CONTEXT-accepted), silent stall of in-flight disbursements on disable, incomplete pricing audit summaries, and intentional non-blocking of pending disbursals when product/mapping disabled.

---

## 2–4. Architecture & traceability

| Control | Status | Evidence |
|---|---|---|
| D4 partner API kill | Complete | `LspStatusService.disable`, `ApiClientJwtSessionValidator`, kill-chain IT |
| Cache eviction | Partial (single-node) | in-process `AuthPrincipalCache` 30s TTL |
| In-flight disbursement on disable | Partial | worker skips; no reject/alert |
| Product version freeze | Complete | V104 + application snapshot; F06 PASS |
| D5 fee model | Complete | `LoanFeeCalculator` + ADR 0004 |
| Mapping disable blocks new loans | Complete | service gates; test gap F03 |

---

## 5. Verified findings

### W3-F01 — In-process auth cache eviction not cluster-safe
- **Severity**: Medium (High if multi-replica before distributed invalidation) · **A7-F01**  
- **Evidence**: `AuthPrincipalCache` ConcurrentHashMap TTL 30s; eviction local only; CONTEXT accepts ≤30s stale  
- **Fix**: Redis pub/sub or drop TTL cache on security path before HA

### W3-F02 — Disabled LSP: disbursement worker silently skips
- **Severity**: Medium · **A7-F02**  
- **Evidence**: `LoanDisbursementWorkerProcessor` returns false; loans remain pending  
- **Fix**: Ops alert + explicit hold/cancel policy

### W3-F03 — Webhooks may continue after LSP disable
- **Severity**: Low–Medium · **A7-F04**  
- **Fix**: Policy on subscription disable / drain-only

### W3-F04 — Status audit missing actor_ip
- **Severity**: Medium · **A7-F05** (D9)

### W3-F05 — PRODUCT_UPDATED audit omits rate/fee
- **Severity**: Medium · **A8-F01**

### W3-F06 — Intake bounds vs live product not version snapshot
- **Severity**: Low · **A8-F02**

### Positives
- Kill chain IT; pricing isolation PASS; D5 fee alignment PASS; reactivation requires client re-enable (intentional)

---

## Remediation
Immediate before HA: W3-F01. 30d: W3-F02/F04/F05. Confirm product-disable vs pending-disbursal policy with business (F11 intentional).
