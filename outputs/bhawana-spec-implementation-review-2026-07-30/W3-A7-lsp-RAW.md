# Wave 3 Agent A7 — D4 Kill Chain Audit (Partner/Tenant Configuration)

**Auditor role:** Bank-grade partner/tenant configuration auditor (read-only)  
**Baseline:** `/Users/siddhant/Desktop/lms` HEAD worktree + spec v1.0.1 + ADR 0002  
**Severity scale (pre-prod):** Critical / High / Medium / Low / Info — calibrated for prototype → pre-prod, not full production hardening

---

## 1. Executive Summary

The D4 partner deactivation kill chain is **substantially implemented and tested** for the primary threat model: **partner API access via client-credentials JWTs**. Disable is atomic, bumps `token_version` on LSP and all API clients, deactivates clients, writes audit, raises `LSP_DISABLED` HIGH alert, and rejects in-flight JWTs on the next request (with explicit cache eviction).

**Gaps that matter before pre-prod:**

| Area | Verdict |
|------|---------|
| Partner API lockout | **Strong** (single-node); **conditional fail-open** (multi-node, ≤30s) |
| LSP staff UI lockout | **Not in scope of kill chain** (documented; staff sessions remain valid) |
| Reactivation + credential rotation | **Strong** (tested end-to-end) |
| Audit completeness | **Partial** (`actor_ip` null on status-change audits) |
| In-flight loans | **Partially bounded** (new origination blocked; disbursement worker silently skips) |
| Webhooks | **Not killed** (outbox continues; subscription unchanged) |
| IP allowlist adjacency | **Orthogonal** (correctly separate; not tied to disable) |

**Overall pre-prod readiness for D4 API kill chain:** **Conditional pass** — ship only with single-replica awareness or distributed cache invalidation; document UI-session and in-flight-loan behavior for operators.

---

## 2. Scope & Methodology

**In scope (per assignment):**
- `LspAdminController`, `LspStatusService`, `LspDirectoryService`
- IP allowlist admin: `LspIpAllowlistAdminController`, `LspUiIpAllowlistAdminController`, `LspAllowlistEnforcementAdminController`
- `LspStatusKillChainIntegrationTest` + related unit/integration tests
- Migrations: `V77`, `V89`, allowlist migrations (`V46`, `V55`, `V79`)
- Frontend kill-chain UI: `LspStatusChangeDialog`, `useUpdateLspStatus`, `page.tsx`
- Governing docs: partner spec (D4/D9), ADR 0002

**Method:** Trace call paths (admin PUT → service txn → JWT validator → cache), cross-check FR-008–FR-016 / NFR-004–NFR-008, read tests as behavioral contract.

**Out of scope:** User admin, product mapping, full webhook feature spec, loan servicing ops paths (referenced only where disable intersects).

---

## 3. Requirements Traceability (D4 / ADR 0002)

| Requirement | Evidence | Status |
|-------------|----------|--------|
| FR-008 `PUT …/status` with reason + note | `LspAdminController.doUpdateStatus` → `LspStatusService.updateStatus` | **Met** |
| FR-009 no-op → `STATUS_UNCHANGED` 409, no audit | `LspStatusService` L72–77; test `disableWritesAuditRowAndFiresAlert` | **Met** |
| FR-010 atomic disable cascade | `@Transactional disable()` L93–114 | **Met** |
| FR-011 per-request JWT enforcement | `ApiClientJwtSessionValidator` L39–79 | **Met** (caveat §7) |
| FR-012 reactivate without client re-enable | `reactivate()` L117–122; rotation in `ApiClientManagementService` L181–183 | **Met** |
| FR-014 audit + `GET …/audit-events` | `writeAudit` + controller L121–126 | **Partial** (no `actor_ip`) |
| FR-016 `LSP_DISABLED` HIGH alert | `emitDisabledAlert` L150–167; integration test | **Met** |
| NFR-004 all-or-nothing txn | Single `@Transactional` on `updateStatus` | **Met** |
| NFR-006 lockout ≤ 1 request | Validator + cache eviction | **Met** single-node; **Gap** multi-node |
| ADR: UI wired to real status API | `LspStatusChangeDialog`, `updateLspStatus` | **Met** |
| Spec clarification: UI users untouched | `ManagedUserJwtPrincipalResolver` — no LSP status check | **As documented** |

---

## 4. API Lockout

### 4.1 Admin entrypoint

```90:118:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspAdminController.java
    @PutMapping("/{lspId}/status")
    public LspResponse updateStatus(
            @PathVariable UUID lspId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateLspStatusRequest request,
            @AuthenticationPrincipal Jwt principal
    ) {
        // ...
        Lsp lsp = lspStatusService.updateStatus(
                lspId,
                request.resolvedStatus(),
                request.resolvedReason(),
                request.note(),
                actorUsername
        );
```

- `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` on controller (L35).
- `DISABLED` alias → `INACTIVE` (L279).
- Idempotency supported (`LSP_STATUS_UPDATE`).
- **Gap:** `HttpServletRequest` not passed — no client IP to service (see §11).

### 4.2 Disable cascade

```93:114:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LspStatusService.java
    private Lsp disable(Lsp lsp, LspStatusChangeReason reason, String note, String actorUsername) {
        lsp.updateStatus(LspStatus.INACTIVE);
        lsp.revokeAllSessions();
        List<ApiClient> clients = apiClientRepository.findByLsp_Id(lsp.getId());
        for (ApiClient client : clients) {
            client.deactivate();
            client.revokeAllSessions();
        }
        apiClientRepository.saveAll(clients);
        Lsp saved = lspRepository.save(lsp);
        for (ApiClient client : clients) {
            authPrincipalCache.evictApiClient(client.getClientId());
        }
        writeAudit(saved, actorUsername, "LSP_DISABLED", reason, note, clients.size());
        emitDisabledAlert(saved, reason, note, clients.size());
        return saved;
    }
```

### 4.3 Per-request enforcement

```59:76:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/ApiClientJwtSessionValidator.java
            if (tokenLspVersion != snapshot.lspTokenVersion()) {
                return failure("LSP_TOKEN_REVOKED", "LSP session is no longer valid.");
            }
            // ...
            if (snapshot.lspStatus() != LspStatus.ACTIVE) {
                return failure("LSP_INACTIVE", "LSP is not active.");
            }
            if (snapshot.apiClientStatus() != ApiClientStatus.ACTIVE) {
                return failure("API_CLIENT_INACTIVE", "API client is not active.");
            }
```

### 4.4 Token issuance block

`ApiClientAuthenticationService.validateActive` (L79–86) rejects inactive client/LSP with generic `BadCredentialsException`.

### 4.5 Refresh path

`LspStatusKillChainIntegrationTest.apiClientRefreshTokenPathRejectsInactiveLsp` — refresh returns 401 after disable. Refresh does **not** bulk-revoke `refresh_token` rows; mitigation is `mintTokenForApiClient` → `validateActive` failure.

### 4.6 Test evidence

`LspStatusKillChainIntegrationTest`: existing JWT → 401 `LSP_TOKEN_REVOKED`; cascade to all clients; tenant isolation; reactivation + rotation path.

**Finding:** Core API lockout is **bank-grade for single-instance deployment**.

---

## 5. UI Lockout

### 5.1 Admin UI (operator)

Kill-chain operator UI is **complete**:
- Warning copy in `LspStatusChangeDialog` (revoke JWTs, deactivate clients, ops alert).
- Required reason + note (Zod `min(1)`).
- Idempotency key per submit.
- Post-success: list cache patch + refetch + prefetch audit; opens audit dialog (`page.tsx` L130–133).

### 5.2 LSP staff UI (partner operators)

**Intentionally NOT locked out.**

`ManagedUserJwtPrincipalResolver.validateSession` checks only `pwdv` and `tv` (user session version) — **no `LspStatus` check**. Spec Session 2026-07-01 and ADR consequences confirm this.

**Implication:** D4 “kill chain” = **partner API credentials**, not full tenant UI access. LSP staff with `LSP_UI_READ`/`LSP_UI_WRITE` can continue using the portal after partner disable unless separately revoked via `UserAdminController`.

**Pre-prod severity:** **Medium** if operators assume “disable partner” means “lock everyone out”; **Info** if documented in runbooks (spec already documents).

---

## 6. Token Version (`token_version`)

### 6.1 Schema (V77)

`/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V77__lsp_api_client_token_version_and_audit.sql`:
- `lsp.token_version BIGINT NOT NULL DEFAULT 0`
- `api_client.token_version BIGINT NOT NULL DEFAULT 0`
- `lsp_audit_event` table + index

### 6.2 Domain behavior

- `Lsp.revokeAllSessions()` / `ApiClient.revokeAllSessions()` increment version (`Lsp.java` L118–120; `ApiClient.java` L180–182).
- JWT mint embeds `tvLsp` / `tvApiClient` (`AuthAuthenticationService` L132–133; `AuthTokenService` L87–88).

### 6.3 Reactivation

`reactivate()` sets LSP `ACTIVE` but **does not** bump `token_version` or re-enable clients. Pre-disable JWTs remain invalid (version mismatch + inactive client). **Correct per D4.**

### 6.4 Refresh tokens

Disable does **not** call `RefreshTokenRepository.revokeAllForApiClient`. Access remains blocked via JWT validator + `validateActive` on re-mint. **Low hygiene gap**, not an access-control bypass.

---

## 7. Cache Eviction

### 7.1 Mechanism

`AuthPrincipalCache`: in-process `ConcurrentHashMap`, **30s TTL** (`TTL_MILLIS = 30_000`).

`LspStatusService.disable()` explicitly `evictApiClient` per client (comment L105–107 acknowledges stale-snapshot risk).

`ApiClientManagementService.rotateSecret` also evicts (L185–187).

### 7.2 Fail-open path (multi-node)

**W3-A7-F01 — High (pre-prod, multi-replica)**

| Condition | Behavior |
|-----------|----------|
| Disable handled on pod A | Eviction only on pod A |
| Partner request hits pod B | Cached `ACTIVE` + old `token_version` may match JWT for **up to 30s** |
| Mitigation today | None (no Redis/pub-sub eviction) |

Single-node: **no practical fail-open** after disable (eviction + DB truth).

**Recommendation before horizontal scale:** distributed cache invalidation or eliminate TTL-based auth snapshots on security path.

---

## 8. In-Flight Loans

| Stage | Behavior on `INACTIVE` LSP | Evidence |
|-------|---------------------------|----------|
| New application | **Blocked** `LSP_NOT_ACTIVE` | `LoanApplicationOnboardingService` L114–119 |
| Auto-approval | **Fails** `LSP_INACTIVE` rule | `LoanAutoApprovalRuleEngine` L65–66 |
| Automated disbursement worker | **Silently skips** (`return false`) | `LoanDisbursementWorkerProcessor` L76–77 |
| Partner API on existing loans | **Blocked** (JWT dead) | Kill-chain tests |

**W3-A7-F02 — Medium:** Loans in `APPROVED_PENDING_DISBURSAL` / `DISBURSEMENT_RETRY` for a disabled LSP are **not auto-rejected or alerted**; worker stops processing with no state transition. Ops must intervene manually.

**W3-A7-F03 — Low:** Internal Bhawana ops paths can still service loans (by design for platform operator); not a partner fail-open.

---

## 9. Webhooks

### 9.1 Kill chain does NOT:
- Set `webhook_enabled = false`
- Cancel pending `webhook_event_outbox` rows
- Block `enqueueIfSubscribed` on LSP status

`WebhookOutboxService.enqueueIfSubscribed` (L74–75) checks only `lsp.isWebhookEnabled()` and event subscription — **not** `LspStatus`.

### 9.2 Dispatch

`WebhookOutboxDispatchExecutor` / `deliverOne` — no `LspStatus` guard found. Pending events **continue delivering** after disable.

**W3-A7-F04 — Low/Medium:** Post-disable outbound webhooks may still fire (in-flight pipeline). May be desirable (final notifications) or surprising (disabled partner still receives events). **Not a partner-initiated fail-open**, but incomplete “communications kill.”

Webhook subscription remains editable on disabled LSP via `LspDirectoryService.updateWebhookSubscription` (no status guard).

---

## 10. Reactivation Path

| Step | Expected (D4) | Implemented | Tested |
|------|---------------|-------------|--------|
| `PUT …/status` `ACTIVE` | LSP active | `reactivate()` | Yes |
| Clients stay inactive | Yes | No cascade on reactivate | Yes |
| Old JWT dead | Yes | Version + inactive client | `reActivationDoesNotReviveExistingTokens` |
| New access via rotate-secret | Yes | `ApiClientManagementService` L181–183 | `reActivationPlusCredentialRotationIssuesWorkingToken` |
| Cache eviction on rotate | Yes | `evictApiClient` | Implicit |

**Verdict:** Reactivation path is **complete and well-tested**.

---

## 11. Audit Trail

### 11.1 What is captured

`LspStatusService.writeAudit` (L125–147):
- `action` (`LSP_DISABLED` / `LSP_REACTIVATED`)
- `actorUsername`, `reason`, `note`, `cascadedClientCount`
- `details_json`: `lspCode`, `status`, `tokenVersion`
- `correlationId` (from `CorrelationIdHolder`)

### 11.2 Gaps

**W3-A7-F05 — Medium (D9):** `actor_ip` is **always null** on status-change audits.

- Column exists (`V89__lsp_audit_event_actor_ip_and_indexes.sql`)
- Entity supports it (`LspAuditEvent` 8-arg constructor)
- `LspStatusService.writeAudit` uses 7-arg constructor (no IP)
- Controller never resolves `ClientIpAddresses` for status PUT (contrast: webhook PUT L162, allowlist controllers L99–104)

### 11.3 Query surface

`GET /api/v1/internal/admin/lsps/{lspId}/audit-events` — tested; UI `LspAuditEventsDialog` displays actions.

### 11.4 Ops alert

`emitDisabledAlert` — HIGH severity, typed `LSP_DISABLED`, includes LSP id/code/context JSON. Manual string concat for `contextJson` (note not escaped) — **W3-A7-F06 Low** display-layer injection only.

---

## 12. IP Allowlist Adjacency

Kill chain **does not** modify allowlists or enforcement flags — correct separation per spec out-of-scope note.

| Controller | Path | Kill-chain interaction |
|------------|------|------------------------|
| `LspIpAllowlistAdminController` | `…/api-ip-allowlist` | Independent; audit via `LspAuditEventService` |
| `LspUiIpAllowlistAdminController` | `…/ui-ip-allowlist` | Same |
| `LspAllowlistEnforcementAdminController` | `…/allowlist-enforcement` | Updates flags; cache invalidation via `IpAllowlistCacheInvalidation` |

**W3-A7-F07 — Low:** `updateEnforcement` has **no audit event** (unlike allowlist CRUD). Not a D4 bypass — partner API already JWT-blocked.

`LspSurfaceIpAllowlistFilter`: 60s local cache for CIDR snapshots; irrelevant once JWT rejected.

---

## 13. Migrations Review

| Migration | Purpose | Kill-chain relevance |
|-----------|---------|---------------------|
| `V77` | `token_version`, `lsp_audit_event` | **Core** |
| `V89` | `actor_ip` + audit indexes | Column unused by status service |
| `V46` | Base IP allowlist | Adjacent |
| `V55` | API client + allowlist | Adjacent |
| `V79` | Surface IP allowlist | Adjacent |
| `V23` | Webhook subscription on `lsp` | Not disabled by kill chain |

---

## 14. Test Coverage Assessment

### 14.1 Strong coverage (`LspStatusKillChainIntegrationTest`)

- JWT 401 after disable (`LSP_TOKEN_REVOKED`)
- RBAC (`SYSTEM_ADMIN` only)
- Reason validation (`REASON_REQUIRED`, `INVALID_REASON`)
- Audit row + no duplicate on `STATUS_UNCHANGED`
- HIGH `LSP_DISABLED` alert
- Multi-client cascade
- Cross-tenant isolation
- Reactivation without token revival
- Reactivation + rotate → working token
- Refresh rejection after disable

### 14.2 Gaps

| Gap | Finding ID |
|-----|------------|
| No `NOTE_REQUIRED` integration test | W3-A7-F08 |
| No cache stale-snapshot / multi-node test | W3-A7-F01 (related) |
| No test for in-flight disbursement on disable | W3-A7-F02 |
| No test for webhook dispatch after disable | W3-A7-F04 |
| No test for LSP UI session surviving disable | W3-A7-F09 (documented behavior) |

### 14.3 Frontend tests

`page.test.tsx` mocks status mutation; `LspStatusChangeDialog` warnings present. `useLspIpAllowlistAdmin.test.tsx` covers allowlist reads.

---

## 15. Findings Register

| ID | Severity | Title | Evidence | Recommendation |
|----|----------|-------|----------|----------------|
| **W3-A7-F01** | **High** (multi-replica pre-prod) | Multi-node `AuthPrincipalCache` fail-open ≤30s | `AuthPrincipalCache` local TTL; eviction only on handling node | Distributed eviction or DB-always validation before cache hit |
| **W3-A7-F02** | **Medium** | In-flight disbursement silently stalls on disable | `LoanDisbursementWorkerProcessor` L76–77 returns false | Reject/alert apps in disbursal-pending when LSP disabled |
| **W3-A7-F03** | **Medium** | LSP staff UI not killed (by design) | `ManagedUserJwtPrincipalResolver`; spec clarification | Runbook + optional future: cascade user session revoke |
| **W3-A7-F04** | **Low/Medium** | Webhooks continue after disable | `WebhookOutboxService`; no status guard on dispatch | Policy: auto-disable subscription or drain-only mode |
| **W3-A7-F05** | **Medium** | Status audit missing `actor_ip` | `LspStatusService.writeAudit`; `LspAdminController` no IP | Pass `HttpServletRequest` → service → audit constructor |
| **W3-A7-F06** | **Low** | Alert `contextJson` string concat | `emitDisabledAlert` L151–157 | Use `ObjectMapper` / parameterized JSON |
| **W3-A7-F07** | **Low** | Allowlist enforcement toggle unaudited | `LspIpAllowlistAdminService.updateEnforcement` | Add `LspAuditEvent` on enforcement change |
| **W3-A7-F08** | **Low** | `NOTE_REQUIRED` untested at HTTP layer | Only `REASON_REQUIRED` in kill-chain test | Add integration test |
| **W3-A7-F09** | **Info** | Refresh tokens not bulk-revoked on disable | No `RefreshTokenRepository` call in disable | Optional hygiene revoke |
| **W3-A7-F10** | **Info** | Admin status endpoints unthrottled | Spec G-5 | Rate-limit privileged mutations pre-prod |
| **W3-A7-F11** | **Info** | No maker-checker on disable | Spec G-6 | SoD workflow if regulatory requirement |
| **W3-A7-F12** | **Low** | Frontend `SUSPENDED` status orphan | `api.ts` `SUPPORTED_BACKEND_STATUSES`; backend enum only `ACTIVE`/`INACTIVE` | Remove or implement `SUSPENDED` |
| **W3-A7-F13** | **Positive** | Core D4 API kill chain complete | ADR 0002 + integration tests | Maintain as regression suite |
| **W3-A7-F14** | **Positive** | Cache eviction on disable implemented | `LspStatusService` L108–110 | Extend to distributed invalidation (F01) |
| **W3-A7-F15** | **Positive** | UI operator flow aligned to ADR | `LspStatusChangeDialog`, audit dialog open | No change required |

---

## Fail-Open Path Summary

```
Partner API request after disable
├── Single node + cache evicted     → CLOSED (401)
├── Multi node + stale cache (≤30s) → OPEN (W3-A7-F01)
├── New token issuance              → CLOSED (validateActive)
├── Refresh token                   → CLOSED (validateActive on mint)
├── LSP staff UI JWT                → OPEN by design (W3-A7-F03)
├── Internal ops/admin loan APIs    → OPEN by design
├── Webhook delivery (outbound)     → OPEN (W3-A7-F04)
└── IP allowlist alone              → N/A once JWT dead
```

---

## Key File Index (absolute paths)

| Artifact | Path |
|----------|------|
| Admin controller | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/LspAdminController.java` |
| Status service | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LspStatusService.java` |
| Directory service | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/LspDirectoryService.java` |
| JWT validator | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/ApiClientJwtSessionValidator.java` |
| Auth cache | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/AuthPrincipalCache.java` |
| Kill-chain tests | `/Users/siddhant/Desktop/lms/backend/src/test/java/com/bhawana/lms/web/LspStatusKillChainIntegrationTest.java` |
| V77 migration | `/Users/siddhant/Desktop/lms/backend/src/main/resources/db/migration/V77__lsp_api_client_token_version_and_audit.sql` |
| ADR | `/Users/siddhant/Desktop/lms/docs/adr/0002-lsp-disable-kill-chain.md` |
| Spec | `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/platform-setup/partner-lsp-onboarding-and-status-management/spec.md` |
| Status UI | `/Users/siddhant/Desktop/lms/frontend/src/features/lsps/components/LspStatusChangeDialog.tsx` |

[REDACTED]