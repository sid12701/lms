# Wave 2 Agent A6 — Partner API Clients & Rate Limiting Security Audit

**Repository:** `/Users/siddhant/Desktop/lms`  
**Mode:** Read-only  
**Specs reviewed:** partner-api-authentication-and-api-clients v1.0.1, api-rate-limiting-and-payload-guards v1.0.1  
**ADR:** `docs/adr/0002-lsp-disable-kill-chain.md`  
**Out of scope:** `SsrfSafeUrlValidator` (outbound webhook SSRF guard only; not on auth perimeter)

---

## 1. Executive Summary

Bhawana LMS implements a credible bank-grade partner credential perimeter: server-generated secrets, BCrypt-only persistence, reveal-once admin flows, JWT token-version revocation, LSP-level IP allowlists, Redis token-bucket rate limiting, and a 10 MB LSP JSON body cap. ADR 0002 kill-chain behavior is implemented and tested.

**Residual risk concentrates in perimeter edge cases:** client-spoofable `X-Forwarded-For` for allowlists, credential-oracle responses on IP denial, refresh-token renewal without IP re-check, PATCH bypass of payload guard, and Redis-unavailability causing hard errors (fail-closed, not fail-open). **15 findings** below (W2-A6-F01–F15).

---

## 2. Scope & Methodology

| In scope | Evidence paths |
|----------|------------------|
| API client lifecycle | `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java`, `ApiClientAdminController.java` |
| Credential auth & lockout | `ApiClientAuthenticationService.java`, `ApiClientLockoutService.java` |
| JWT / token version | `ApiClientJwtSessionValidator.java`, `AuthTokenService.java`, `AuthPrincipalCache.java` |
| Kill switch (ADR 0002) | `LspStatusService.java`, `docs/adr/0002-lsp-disable-kill-chain.md` |
| IP allowlist | `LspSurfaceIpAllowlistFilter.java`, `LspSurfaceIpAllowlistService.java`, `ClientIpAddresses.java` |
| Rate limiting | `RateLimitFilter.java`, `RateLimitConfig.java`, `KeyStrategy.java`, `application.yml` |
| Payload guard | `LspApiPayloadSizeFilter.java` |
| Tests | `ApiClientTokenLockoutIntegrationTest`, `RateLimitFilterIntegrationTest`, `LspApiPayloadSizeFilterTest`, `Issue64LspSurfaceIpAllowlistIntegrationTest`, `LspStatusKillChainIntegrationTest`, `ApiClientAdminControllerTest` |

Method: spec-to-code trace, filter-chain order analysis, failure-mode reasoning, test coverage mapping.

---

## 3. Threat Model (Partner Perimeter)

| Threat | Primary control | Residual |
|--------|-----------------|----------|
| Secret theft at rest | BCrypt `secret_hash` only | Rotation grace keeps prior hash briefly |
| Brute-force `clientId`/`secret` | Per-client lockout (5/15m) + IP rate limit (10/min) | Lockout is per-client, not per-IP |
| Stolen JWT | `tvLsp` / `tvApiClient` + status checks | 30s principal cache window if eviction missed |
| Stolen refresh cookie | SHA-256 hashed storage, rotation | No IP allowlist on `/auth/refresh` |
| IP bypass | LSP API CIDR allowlist | XFF trust; filter fail-open if `lspId` missing |
| DoS / payload bomb | 10 MB cap + per-LSP write rate limit | PATCH uncapped; Redis down → 500 |
| Credential leakage | Reveal-once; audit excludes secret | React in-memory banner; HTTPS/cookie config dependent |

---

## 4. Control Matrix (Spec → Implementation)

| Spec FR/NFR | Status | Evidence |
|-------------|--------|----------|
| FR-004 hashed secrets | **PASS** | `ApiClientManagementService` L75–82, `SecurityConfig` BCrypt |
| FR-005 reveal-once | **PASS** | `ApiClientAdminController` idempotency + `secretHolder` pattern |
| FR-008 JWT claims + token versions | **PASS** | `AuthAuthenticationService` L125–134 |
| FR-009 validator + cache eviction | **PASS** | `ApiClientJwtSessionValidator`, evict on update/rotate/disable |
| FR-011 IP allowlist | **PARTIAL** | Token + `/lsp/**`; refresh uncovered |
| FR-014 rotation grace | **PASS** | Default 300s, dual-hash compare |
| FR-020 lockout | **PASS** | `ApiClientLockoutService`, `ApiClientTokenLockoutIntegrationTest` |
| NFR-001 auth rate limit | **PASS** | `auth-token` rule 10/min IP |
| NFR-002 per-LSP write limit | **PASS** | `lsp-write` 60/min `LSP` key |
| NFR-004 payload cap | **PARTIAL** | POST/PUT only |
| FR-016 LSP disable cascade | **PASS** | `LspStatusService.disable`, ADR 0002 |

---

## 5. Client Secret Hashing & Generation

**Evidence**

```246:250:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ApiClientManagementService.java
    private static String generateClientSecret() {
        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
    }
```

- 256-bit entropy via `SecureRandom` + Base64URL — **strong**.
- `PasswordEncoder` = `BCryptPasswordEncoder()` default strength (`SecurityConfig.java`) — **acceptable**.
- List/admin DTOs expose metadata only; `ApiClientAdminControllerTest` asserts no `clientSecret` on list.
- `ApiClientAdminControllerCreateAuditTest` asserts cleartext secret **not** in audit JSON — **good**.

**Finding:** None for hashing/generation fundamentals.

---

## 6. Secret Rotation & Grace Window

**Evidence:** `ApiClient.rotateSecret()` increments `token_version`, stores `previous_secret_hash` + `previous_secret_valid_until`; `ApiClientAuthenticationService.matchesAnyActiveSecret()` accepts either hash within grace; expired previous cleared opportunistically.

| Behavior | Verdict |
|----------|---------|
| New JWTs get bumped `tvApiClient` | Revokes outstanding access tokens immediately (post cache eviction) |
| Grace window (default 300s) | Partner-friendly; widens compromise window for old secret |
| Idempotent rotate replay | `clientSecret` null on replay — tested in `ApiClientAdminControllerTest` |

**W2-A6-F12 (LOW):** Idempotency-Key optional on create/rotate when header omitted (spec G-002).

---

## 7. Kill Switch & Token Revocation (ADR 0002)

**Evidence:** `LspStatusService.disable()`:
1. Sets LSP `INACTIVE`, `lsp.revokeAllSessions()` (bumps `lsp.token_version`)
2. Deactivates all API clients, `client.revokeAllSessions()` each
3. `authPrincipalCache.evictApiClient()` per client
4. Audit + `LSP_DISABLED` alert

`ApiClientJwtSessionValidator` enforces `tvLsp`, `tvApiClient`, and ACTIVE statuses.

**Test:** `LspStatusKillChainIntegrationTest.disablingLspCausesExistingApiClientTokenTo401OnNextRequest` → `LSP_TOKEN_REVOKED`.

**W2-A6-F13 (LOW):** Individual API-client disable via `PUT` does **not** bump `token_version`; relies on status check + cache eviction (30s stale-cache risk if eviction regresses).

**W2-A6-F14 (INFO/PASS):** Kill chain matches ADR 0002; cache eviction explicitly documented in `LspStatusService` comments.

---

## 8. Credential Lockout (API Client)

**Evidence**

```38:44:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/ApiClientLockoutService.java
    public void registerFailedAttempt(UUID apiClientId, Instant now) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                apiClientRepository.findById(apiClientId).ifPresent(client -> {
                    client.registerFailedAuth(now, MAX_FAILED_ATTEMPTS, LOCK_DURATION);
                    apiClientRepository.save(client);
                }));
    }
```

- `REQUIRES_NEW` survives auth transaction rollback — **excellent pattern**.
- Throttle checked **before** secret compare — correct secret rejected during lockout with same `INVALID_CREDENTIALS` — **no oracle via lockout** (`ApiClientTokenLockoutIntegrationTest`).
- Unknown `clientId` → immediate `BadCredentialsException` without lockout counter — **no user enumeration via lockout state**.

**W2-A6-F15 (LOW):** Fixed 5/15m policy; no admin unlock/visibility (spec G-009). Brute-force across many `clientId`s only limited by IP rate limit (10/min).

---

## 9. JWT Validation & Principal Cache

| Control | TTL / behavior |
|---------|----------------|
| `AuthPrincipalCache` | 30s TTL |
| Eviction triggers | Client update, rotate, LSP disable cascade |
| Validator claims | `tvLsp`, `tvApiClient`, LSP/client ACTIVE |

**W2-A6-F13** (above) covers disable-without-version-bump.

**PASS:** Secret rotation and LSP disable both evict cache; kill-chain test proves immediate 401 on next request.

---

## 10. IP Allowlist — Fail-Open vs Fail-Closed

### Decision logic (`LspSurfaceIpAllowlistService.evaluate`)

| Condition | Decision |
|-----------|----------|
| Empty CIDRs + enforcement **off** | **ALLOW** (fail-open) |
| Empty CIDRs + enforcement **on** | **DENY** `IP_ENFORCEMENT_EMPTY_LIST` (fail-closed) |
| Non-empty CIDRs, IP not matched | **DENY** `IP_NOT_ALLOWED` |
| Admin enables enforcement with empty list | **Blocked** `ALLOWLIST_EMPTY_CANNOT_ENFORCE` (`Issue64` test) |

### Filter bypass paths

```65:67:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/LspSurfaceIpAllowlistFilter.java
        if (lspId == null || surface == null) {
            filterChain.doFilter(request, response);
            return;
        }
```

**W2-A6-F04 (MEDIUM):** Request-time allowlist **fail-open** when JWT lacks resolvable `lspId` or LSP role surface — request proceeds without IP check.

### Client IP resolution

```15:28:/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/common/web/ClientIpAddresses.java
    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            ...
                return first;
        }
        ...
        return request.getRemoteAddr();
    }
```

**W2-A6-F01 (HIGH):** `X-Forwarded-For` honored without trusted-proxy boundary. Direct exposure allows attackers to spoof an allowed IP. `Issue64LspSurfaceIpAllowlistIntegrationTest.apiClientTokenRejectsXForwardedForWhenRemoteAddrWouldPass` **encodes this behavior as expected** — deployment must terminate TLS at a proxy that **overwrites** XFF.

### Token issuance vs request filter

- Token: `assertApiTokenIssuanceAllowed` after credential success
- Requests: `LspSurfaceIpAllowlistFilter` after Bearer auth
- 60s in-process cache with `IpAllowlistCacheInvalidation.afterCommit` — **tested** for immediate effect on token path

**W2-A6-F02 (MEDIUM):** Valid credentials from blocked IP return **403** `API_CLIENT_IP_NOT_ALLOWED` vs **401** `INVALID_CREDENTIALS` — **credential oracle** (spec G-010).

**W2-A6-F03 (LOW):** IP-denied token attempts do not write `API_CLIENT_TOKEN_FAILED` audit (only `BadCredentialsException` caught in `issueClientCredentialsToken`).

**W2-A6-F05 (MEDIUM):** `POST /api/v1/auth/refresh` for API clients performs **no IP allowlist check** — stolen `lms-refresh` cookie can renew sessions from any IP (`AuthAuthenticationService.refreshSession` → `mintTokenForApiClient` only checks active status).

---

## 11. Rate Limiting — Redis & Fail Modes

### Configuration (`application.yml`)

| Rule | Path | Key | Default |
|------|------|-----|---------|
| `auth-token` | `/api/v1/auth/token` POST | IP | 10/min |
| `lsp-write` | `/api/v1/lsp/**` POST/PUT/PATCH/DELETE | LSP (`lspId` JWT) | 60/min |

### Filter placement

`RateLimitFilter` registered **after** `BearerTokenAuthenticationFilter` — correct for `LSP` key strategy on authenticated partner writes.

### Fail-open (by design)

`KeyStrategy`: unresolvable IP/subject/LSP → empty bucket list → **pass unlimited** (spec FR-003, EC-002).

### Redis unavailable

`RateLimitFilter.evaluate()` has **no try/catch** around `bucket.tryConsumeAndReturnRemaining()` — Redis failure propagates as **HTTP 500**, not silent pass-through.

**W2-A6-F06 (MEDIUM — availability, security-positive):** Redis down = **fail-closed** (errors), **not** fail-open. Matches spec gap G-2 observation; partner auth/token path becomes unavailable rather than unthrottled.

**W2-A6-F07 (MEDIUM):** `KeyStrategy.IP` uses `request.getRemoteAddr()` only — **not** `ClientIpAddresses.resolve()`. Behind reverse proxy, all partners may share one bucket; allowlist and rate-limit see **different** client IPs. Spec G-3.

**W2-A6-F08 (LOW):** `app.rate-limit.enabled=false` disables filter **and** Redis beans entirely — operational misconfig removes all throttling.

**PASS:** 429 contract with `RATE_LIMIT_EXCEEDED`, `Retry-After`, ops alert emission — `RateLimitFilterIntegrationTest`.

---

## 12. Payload Bomb Protection

**Evidence:** `LspApiPayloadSizeFilter` — `MAX_JSON_BYTES = 10MB`; rejects declared `Content-Length`; wraps stream for chunked/under-declared bodies.

**Tests:** `LspApiPayloadSizeFilterTest` — declared oversize 413, chunked oversize throws `PayloadTooLargeException`, within-cap readable.

Filter order: **before** Bearer auth — oversized bodies rejected early (comment notes unauthenticated bodies not consumed by controllers).

**W2-A6-F09 (MEDIUM):** Guard applies to **POST/PUT only**. `LspBorrowerApiController` exposes `@PatchMapping` — PATCH bodies **not** capped despite `lsp-write` rate limit covering PATCH.

**W2-A6-F10 (LOW):** GET with large body on `/api/v1/lsp/**` not filtered (spec EC-008 — accepted).

---

## 13. Credential Leakage Surfaces

| Surface | Assessment |
|---------|------------|
| DB | `secret_hash` only — **PASS** |
| Admin list API | No secret fields — **PASS** |
| Audit (`api_client_audit_event`) | Secret excluded — **PASS** (`ApiClientAdminControllerCreateAuditTest`) |
| Auth audit | Records clientId, not secret — **PASS** |
| Admin create/rotate response | One-time cleartext in HTTPS response — expected |
| Frontend | Secret in React state + reveal banner (`page.tsx`); **not** in `localStorage` for admin session tokens (`session-storage.ts` excludes bearer) — **acceptable** with XSS hygiene |
| Refresh cookie | `httpOnly`, `SameSite=Strict`, `secure` configurable (`RefreshCookieFactory`) — **good** |
| Postman/e2e fixtures | Test secrets in repo fixtures — dev-only risk |
| Logs | No evidence of secret logging in auth path — **PASS** |

**W2-A6-F11 (LOW):** Revealed secret persists in page React state until operator dismisses banner — clipboard/XSS exposure window.

---

## 14. Test Coverage Assessment

| Area | Test file | Coverage quality |
|------|-----------|------------------|
| Lockout | `ApiClientTokenLockoutIntegrationTest` | **Strong** — throttle, reset, correct-secret-during-lockout |
| IP allowlist | `Issue64LspSurfaceIpAllowlistIntegrationTest` | **Strong** — token issuance, LSP route, XFF, enforcement guard |
| Kill chain | `LspStatusKillChainIntegrationTest` | **Strong** — token revocation on LSP disable |
| Payload guard | `LspApiPayloadSizeFilterTest` | **Good** — unit-level; no integration 413 test |
| Rate limit | `RateLimitFilterIntegrationTest` | **Moderate** — mocked Redis; no Redis-down test |
| Secret in audit | `ApiClientAdminControllerCreateAuditTest` | **Good** |
| Idempotency replay | `ApiClientAdminControllerTest` | **Good** |
| Redis failure / PATCH payload / refresh+IP | — | **Gap** |

---

## 15. Findings Register

| ID | Sev | Title | Evidence | Recommendation |
|----|-----|-------|----------|----------------|
| **W2-A6-F01** | **HIGH** | XFF spoofing bypasses LSP API allowlist without trusted proxy | `ClientIpAddresses.java`; `Issue64` test expects XFF override | Terminate at proxy that strips client XFF; or gate `ClientIpAddresses` on trusted hop count / `Forwarded` RFC 7239 |
| **W2-A6-F02** | **MEDIUM** | Credential oracle: valid secret + bad IP → 403 not 401 | `AuthAuthenticationService.issueClientCredentialsToken` L119; `GlobalExceptionHandler` L326–331 | Return uniform 401 for all token failures; log IP denial internally |
| **W2-A6-F03** | **LOW** | No auth audit on IP-denied token issuance | Same flow — only `BadCredentialsException` audited | Record `API_CLIENT_TOKEN_FAILED` with reason `IP_NOT_ALLOWED` |
| **W2-A6-F04** | **MEDIUM** | IP allowlist filter fail-open when `lspId`/surface null | `LspSurfaceIpAllowlistFilter` L65–67 | Fail-closed on `/api/v1/lsp/**` when authenticated but tenant unresolved |
| **W2-A6-F05** | **MEDIUM** | Refresh path skips IP allowlist for API clients | `AuthController.refresh` → `refreshSession` — no allowlist call | Enforce `assertApiTokenIssuanceAllowed` (or equivalent) on API-client refresh |
| **W2-A6-F06** | **MEDIUM** | Redis outage fails closed (500), not fail-open | `RateLimitFilter` L107–109; spec EC-006 | Document runbook; consider circuit-breaker with conservative in-memory fallback |
| **W2-A6-F07** | **MEDIUM** | Rate-limit IP ≠ allowlist IP resolution | `KeyStrategy.clientIp` vs `ClientIpAddresses` | Unify on `ClientIpAddresses` behind trusted-proxy config |
| **W2-A6-F08** | **LOW** | `app.rate-limit.enabled=false` removes all throttling | `RateLimitConfig` `@ConditionalOnProperty` | Enforce enabled in prod via config policy / startup guard |
| **W2-A6-F09** | **MEDIUM** | PATCH bypasses 10 MB payload guard | `LspApiPayloadSizeFilter` L46; `LspBorrowerApiController` `@PatchMapping` | Include PATCH (or all body-bearing methods) in filter |
| **W2-A6-F10** | **LOW** | GET bodies uncapped on LSP surface | Spec EC-008 | Accept or add global cap if needed |
| **W2-A6-F11** | **LOW** | Cleartext secret in admin UI React state until dismissed | `frontend/src/features/api-clients/page.tsx` | Clear on navigation; minimize banner lifetime |
| **W2-A6-F12** | **LOW** | Optional idempotency on create/rotate | `ApiClientAdminController` L64–66, L139–141 | Require `Idempotency-Key` for mutating admin calls |
| **W2-A6-F13** | **LOW** | Client disable without `token_version` bump | `ApiClientManagementService.updateClient` | Increment `token_version` on disable for defense-in-depth |
| **W2-A6-F14** | **INFO** | ADR 0002 kill chain correctly implemented | `LspStatusService`, `LspStatusKillChainIntegrationTest` | Maintain eviction on all revocation paths |
| **W2-A6-F15** | **LOW** | Lockout not configurable; no admin unlock | `ApiClientLockoutService` constants | Expose config + ops unlock for incident response |

---

## Appendix A — Filter Chain Order (Partner Path)

```
Request
  → LspApiPayloadSizeFilter (POST/PUT /lsp/** only)
  → BearerTokenAuthenticationFilter
  → AuthenticationTenantScopeFilter
  → LspSurfaceIpAllowlistFilter (/lsp/**)
  → RateLimitFilter (if enabled)
  → Controller
```

`/api/v1/auth/token`: payload filter N/A → rate limit (IP via `remoteAddr`) → controller (credential auth → IP allowlist → mint JWT + refresh cookie).

---

## Appendix B — SsrfSafeUrlValidator

**Not in auth perimeter.** Used for outbound webhook delivery (`HttpWebhookDeliveryClient.java`). No finding for partner API scope.

---

## Appendix C — Spec Gap Cross-Reference

| Spec gap | Finding |
|----------|---------|
| G-002 optional idempotency | W2-A6-F12 |
| G-003 rate-limit IP = remoteAddr | W2-A6-F07 |
| G-006 LSP-level not per-client IP | Accepted design |
| G-009 lockout not configurable | W2-A6-F15 |
| G-010 failure reason uniformity | W2-A6-F02, F03 |
| G-2 Redis no fallback | W2-A6-F06 |

---

**Audit conclusion:** Implementation is **substantially production-aligned** for a prototype moving toward bank-grade partner APIs. Prioritize **F01** (proxy/XFF hardening), **F02** (oracle removal), **F05** (refresh IP enforcement), and **F09** (PATCH payload cap) before external partner onboarding at scale.

[REDACTED]