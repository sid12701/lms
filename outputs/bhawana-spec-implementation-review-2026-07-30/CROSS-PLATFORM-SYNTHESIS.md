# Cross-Platform Synthesis & Remediation Backlog

**Review**: Bhawana LMS bank-grade architecture review  
**Date**: 2026-07-30  
**Baseline**: LMS `bfd571f` + dirty worktree (D3 borrower lock)  
**Specs**: 32 under `ferratum-products-specs-res/areas/bhawana`  
**Oracle**: D1–D10 + RBI/statutory guidance + fintech practice (specs are largely as-is maps)  
**Posture**: Pre-prod / mock rails (approved)

Artifacts: `W1`…`W8-*.md`, agent RAW dumps, `AUDIT-LEDGER.md`

---

## 1. Overall assessment

This is a **deliberately engineered modular monolith**, not a generic CRUD scaffold. Evidence: dual-datasource RLS tenancy (ADR 0005), LSP kill chain (ADR 0002), product version freeze, disbursement intent before bank call, lease-fenced API idempotency, StatusWriter as sole status mutation owner, NUMERIC money, append-oriented audits.

It is **not yet bank-production-ready for live money or collections SoR**. The deferred register already names the right P0 stack (S5/S6/S13/S14). This review independently verified those gaps and found additional **reachable** defects: session kill on user deactivate, refresh survival after password change, foreclosure settlement races, admin/worker disbursement gate asymmetry, DPD alert blind spot, MIS stuck-PROCESSING, webhook redirect SSRF residual, KPI mislabel (`totalDisbursed` → “MTD”).

**Verdict:** Suitable for **management-review / synthetic UAT** with containment. **Block live ICICI and partner pilot** until Immediate + go-live P0 items close. Spec↔code agreement is weak evidence of quality because specs document as-is behavior.

---

## 2. Cross-area inconsistencies

| Theme | Inconsistency |
|---|---|
| AuthZ status checks | API clients reject INACTIVE; managed users do not (W2-F01) |
| Disbursement gates | Worker full preflight; admin initiate partial (W5-F01) |
| Document gates | Initiate vs worker LMS-managed content mismatch (W4-F05) |
| Idempotency | Payments require key; ops disbursement/FC optional; foreclosure payment null key |
| PII | Some MIS masking; LSP loan APIs raw PAN; FE `borrowerPanMasked` false friend |
| Tenancy elevation | LSP HTTP tenant-scoped then admin-elevate for writes (W1-F07) |
| DPD | W5 alerts miss DISBURSED; W7 UI maps DPD90+ as “overdue” |
| Glossary vs code | CONTEXT three-leg bank model vs single composite mock adapter |
| Spec baseline | Specs stamp `2269d064`; code at `bfd571f` + dirty D3 fix |

---

## 3. Shared systemic risks

1. **Single SYSTEM_ADMIN break-glass** for money + identity (S14 / W2-F04 / W5-F03 / W7-F01)  
2. **Mock rail always registered** (S6) — deployment footgun  
3. **No financial ledger / reversal model** (S13 / W8)  
4. **In-process auth principal cache** — HA stale ≤30s (W3-F01)  
5. **As-is specs** hide design debt by documenting it as intended behavior  
6. **Compose-only ops posture** — DR/backup/restore undocumented  

---

## 4. Default-driven patterns (retain vs redesign)

| Pattern | Judgment |
|---|---|
| Coarse 6-role RBAC | Retain (product); delete dead `app_permission` or implement entitlements |
| CSRF off + SameSite Strict | Retain with deployment contract; harden refresh CSRF later |
| Full EMI only (D6) | Retain as product; plan S13 before real collections |
| Polling workers not broker | Retain until HA scale; prove leases under multi-replica |
| HS256 JWT | Redesign before production (asymmetric keys) |
| Unconditional mock `@Service` | Must redesign (S6) before live |

---

## 5. Missing platform capabilities

- Maker-checker / STP caps  
- Approval-time beneficiary freeze  
- Mock/live exclusive provider mode  
- Receipt/allocation/suspense/reversal ledger  
- Malware quarantine on documents  
- CKYC / bureau  
- Three-way bank recon  
- DWH/CDC publication  
- Distributed session/cache invalidation  
- Production DR runbooks  

---

## 6. Target architecture (condensed)

```
Partner API ──► tenant DS + RLS ──► domain services
Admin/Ops   ──► admin DS ──► same domain (explicit elevation allowlist)
Money path  ──► intent (frozen beneficiary) ──► exclusive provider
            ──► maker-checker / caps ──► outcome applier ──► subledger
Collections ──► receipts → allocations → reversals → period close
Audit       ──► append-only + explorer covering identity/money/docs
Edge        ──► trusted proxy, AV quarantine, SSRF with pinned IP / no redirects
```

---

## 7. Platform standards to adopt (ADR backlog)

1. Session termination on status/credential change  
2. Money-mutation idempotency mandatory + domain unique fences  
3. Admin elevation allowlist + architecture tests  
4. Provider mode exclusivity (S6)  
5. Financial dating + quote expiry  
6. PII mask/reveal matrix (S15)  
7. Document AV quarantine  
8. HA auth cache invalidation  
9. Spec ownership for tenancy + idempotency (currently unowned)

---

## 8. Test strategy gaps

- Foreclosure concurrent execute  
- INACTIVE user session rejection  
- Password-change old refresh rejection  
- Intent claimBatch concurrency  
- Redis-down rate-limit behavior  
- Webhook redirect SSRF  
- MIS PROCESSING reclaim  
- DPD on DISBURSED past-due  
- Commit dirty D3 concurrency IT  

---

## 9. Prioritized remediation backlog

### Immediate containment (days)

| Item | Impact | Effort | Areas | Verify |
|---|---|---|---|---|
| Commit D3 borrower lock + concurrency tests | Prevents double open loan account | S | W4 | Concurrency IT green |
| Foreclosure: FOR UPDATE + non-null payment idempotency | Stops duplicate settlements | S | W1/W5 | Dual-execute test |
| Deactivate user → revoke sessions + status check in JWT | Compromised-user kill | S | W2 | Integration test |
| Password change → revoke all refresh | Credential rotation integrity | S | W2 | Old cookie 401 |
| Admin disburse uses same preflight as worker | Wrong-party payout control | S | W5 | Unit/IT |
| Fix home KPI mapping (MTD vs total; DPD label) | Ops decisions | S | W7 | FE test |
| Disable webhook HTTP redirects / pin IP | SSRF | S | W6 | SSRF tests |

### 30-day improvements

| Item | Impact | Effort | Deps |
|---|---|---|---|
| Require Idempotency-Key on all money ops | Replay safety | S | — |
| Trusted-proxy XFF contract | Allowlist integrity | M | Infra |
| MIS PROCESSING reclaim + retention policy | Ops resilience / DPDP | M | — |
| Align FE OPS permissions; mask LSP PAN (S15) | Privacy | M | Product |
| Document AV quarantine | Malware | M | Storage |
| DPD include DISBURSED past due | Collections signal | S | — |
| Revoke tenant DELETE on audit/money tables | Defense-in-depth | S | — |
| Idempotency reconstructors for money ops | Crash recovery | M | — |

### 60–90 day foundational

| Item | Impact | Effort | Deps |
|---|---|---|---|
| **S5** beneficiary freeze | Payout integrity | M | V114+ |
| **S6** mock/live exclusivity | Deployment safety | M | Before ICICI |
| **S14** maker-checker + caps | Dual control | L | Risk thresholds |
| **S13** receipt ledger | Collections SoR | L | Product D1b |
| Technical-retry debit-return gate | Double-pay | M | ICICI status model |
| HA auth cache invalidation | Multi-replica | M | Redis |
| Asymmetric JWT + MFA for admins | Identity | M | — |

### Longer-term evolution

| Item | Notes |
|---|---|
| Three-way recon MVP | After S13; needs bank/LSP ingest decisions |
| CKYC SFTP | Compliance track parallel |
| DWH/CDC | After operational SoR stable |
| Extract integrations service | Optional; monolith OK until scale |
| Partial payments / promotions | Product expansion beyond D6 |

---

## 10. Go-live gates (explicit)

| Gate | Required |
|---|---|
| Synthetic UAT / demo | Immediate containment recommended; deferred S* acceptable if labeled |
| Partner pilot (API) | W2 session fixes, S15 PAN, SSRF harden, rate-limit proxy contract |
| Live ICICI | **S5 + S6 + S14 + retry policy + admin=worker preflight** |
| Collections system of record | **S13** then recon roadmap |
| Multi-replica HA | Distributed cache eviction + worker lease proof |

---

## 11. What was not reviewed (limits)

- Live ICICI sandbox traffic / production secrets  
- Real R2 retention configs outside code  
- Load/perf beyond static review  
- Legal opinion on RBI licence status  
- Sibling Ferratum European areas (out of scope)  
- Every line of 130k `table-reference.md` (sampled via migrations + schema spec)  
- Full Playwright E2E execution in this pass  

---

## 12. Subagent coverage record

| Wave | Agents | Lead verification |
|---|---|---|
| W1 | A1, A2, A3, A22 | Criticals downgraded; foreclosure High promoted |
| W2 | A4, A5, A6 | Session Highs confirmed; maker-checker calibrated |
| W3 | A7, A8 | Kill chain + versioning confirmed |
| W4 | A9, A10, A11 | Dirty D3 lock treated as in-scope fix |
| W5 | A13, A14, A15 | Money Highs + research sequencing |
| W6 | combined | PAN/SSRF/MIS spot-checked |
| W7 | combined | KPI mislabel confirmed in FE map |
| W8 | combined | Gap honesty + sequencing accepted |
