# W2 — Identity & Access (Verified)

**Status**: Verified (lead agent)  
**Date**: 2026-07-30  
**Agents**: [A4 Auth](d084e374-30e7-418c-9f55-b30ca2f37c9b), [A5 RBAC](ce8fad1c-38a7-4c51-991e-d245ac3e2d68), [A6 Partner+RateLimit](dc245087-7e9d-48ee-9748-38c2d815e102)  
**Specs**: `internal-authentication-and-sessions`, `user-role-and-permission-management`, `partner-api-authentication-and-api-clients`, `api-rate-limiting-and-payload-guards`  
**Baseline**: LMS `bfd571f`

---

## 1. Executive assessment

Identity perimeter is **deliberately engineered** for a prototype approaching bank use: HttpOnly refresh cookies, hashed refresh storage, rotation, auth audit, API-client BCrypt secrets, token-version kill chain (ADR 0002), Redis Bucket4j limits, LSP IP allowlists. Coarse six-role RBAC matches the product model (D1/D8).

**Two High session-lifecycle defects** survive lead verification: deactivating a user does not revoke sessions or check status on JWT validation; required password change does not revoke prior refresh tokens (comment claiming `tokenVersion` bump is incorrect — `AppUser.changePassword` only updates `passwordChangedAt`).

Maker-checker absence is **documented deferred risk (S14)** — High at live rails, Medium under approved mock/pre-prod posture.

---

## 2. Purpose and actors

Human staff (SYSTEM_ADMIN, OPS_USER, PRODUCT_ADMIN), LSP UI (READ/WRITE), partner machines (LSP_API_CLIENT). AuthN gates SPA + partner token issuance; AuthZ is role-based `@PreAuthorize`.

---

## 3. End-to-end path

```
Login → AuthAuthenticationService → access JWT (memory SPA) + HttpOnly refresh cookie
Request → Bearer JWT → ManagedUserJwtPrincipalResolver / ApiClientJwtSessionValidator
      → AuthenticationTenantScopeFilter → @PreAuthorize roles
Partner token → client_id/secret → lockout + IP allowlist → JWT with tv + lspId
Kill chain → LSP inactive / client inactive / tv bump → validator rejects
```

---

## 4. Traceability matrix (selected)

| Requirement | Spec | Backend | FE | DB | Tests | Status |
|---|---|---|---|---|---|---|
| Session login/refresh/logout | P1 | Auth* | features/auth | refresh_token, auth_event_audit | Auth* tests | Partial (F01/F02) |
| SPA memory access token | P1 / CONTEXT | — | session-storage | — | session-storage.test | Complete |
| Role model (6 roles) | README / P2 | RoleCode + @PreAuthorize | guards, permissions.ts | app_role | SecurityConfigTest | Complete (coarse) |
| Fine-grained permissions | P2 schema | **unused** | overstated OPS perms | app_permission dead | — | Contradictory / unused |
| API client auth + kill chain | I1 / ADR 0002 | ApiClient* | api-clients | api_client | KillChain IT | Complete |
| Rate limit + payload | P3 | RateLimit*, PayloadSize | — | Redis | RateLimit* IT | Partial (PATCH, XFF) |
| Maker-checker | — / S14 deferred | absent | — | — | — | Missing (accepted deferral) |

---

## 5–12. Reviews (condensed)

**AuthN:** Strong cookie/JWT split; refresh hashed; rotation; 428 must-change-password; brute-force lockout scheduled (not inline). Gaps: INACTIVE ignored; password-change refresh survival; CSRF disabled (SameSite=Strict mitigation); distributed brute-force alert-only; no MFA; HS256.

**AuthZ:** Coarse roles only. Money mutations SYSTEM_ADMIN-only (good). OPS_USER read-heavy. FE `permissions.ts` overstates OPS money capabilities — backend still denies. Bootstrap can be dual-role SYSTEM_ADMIN+OPS_USER. `app_permission` tables unused.

**Partner API:** Secrets BCrypt; reveal-once; lockout; IP allowlist; kill chain tested. High: XFF trust without trusted-proxy gate. Medium: credential oracle on IP deny; refresh skips IP re-check; PATCH uncapped; allowlist fail-open if lspId missing.

**Rate limit:** Fail-**closed** on Redis errors (500) — security-positive, availability risk. IP key uses remoteAddr ≠ allowlist ClientIpAddresses.

**Multi-tenancy:** LSP principals cannot hold admin scope (ADR 0005). Role/LSP assignment consistency validated.

**Financial:** No money movement in W2; SoD gap deferred S14.

**Ops:** Bootstrap sync endpoint; default password `ChangeMe123!` in properties — deployment hygiene risk.

---

## 13. External research

| Source | Relevance |
|---|---|
| OWASP ASVS V2/V3 session | Session termination on privilege/status change; refresh reuse detection |
| NIST SP 800-63B | Password change should invalidate existing sessions |
| RBI IT outsourcing / cyber guidance (assumed) | Privileged access dual control — maps to S14 maker-checker |

---

## 14. Default-driven register

| Item | Class |
|---|---|
| CSRF disabled + SameSite | Common SPA pattern; documented tradeoff |
| Dead `app_permission` tables | Framework/scaffold residue |
| Coarse RBAC | Deliberate product model |
| Optional rate-limit disable property | Config footgun |
| HS256 JWT | Prototype convenience |

---

## 15. Verified findings

### W2-F01 — INACTIVE users retain valid JWT + refresh
- **Severity**: High · **Confidence**: High · **A4-F01** · Lead verified  
- **Evidence**: `UserAdminService.updateUser` revokes only on `rolesChanged` (208–217); `ManagedUserJwtPrincipalResolver.validateSession` checks `pwdv`/`tv` only (71–93); contrast `ApiClientJwtSessionValidator` checks ACTIVE (70–75)  
- **Scenario**: Admin disables compromised user; sessions remain usable until JWT expiry / refresh lifetime  
- **Fix**: On INACTIVE → `sessionRevocationService.revokeAllSessions` + cache evict; reject non-ACTIVE in `validateSession`

### W2-F02 — Password change does not revoke refresh tokens
- **Severity**: High · **Confidence**: High · **A4-F02** · Lead verified  
- **Evidence**: `AppUser.changePassword` (158–161) updates hash/`passwordChangedAt` only — **does not** bump `tokenVersion` (only `revokeAllSessions` at 188–190 does); `completeRequiredPasswordChange` (235–239) evicts cache but does not revoke refresh rows; `rotateRefreshToken` (164–188) accepts unrevoked refresh and mints new access with current `pwdv`  
- **Note**: Misleading comment at UserAdminService 237–238 claims token version bump  
- **Fix**: Revoke all refresh tokens (and bump `tv`) on password change; test old cookie → 401

### W2-F03 — X-Forwarded-For trusted without proxy boundary
- **Severity**: High · **Confidence**: High · **A6-F01** · Lead accepted  
- **Evidence**: Agent + Issue64 test encodes XFF override as expected behavior  
- **Fix**: Trusted-proxy configuration; strip client-supplied XFF at edge

### W2-F04 — No maker-checker on money / identity admin
- **Severity**: Medium (pre-prod) / **High at live rails** · **A5-F03 Critical downgraded**  
- **Evidence**: Deferred S14; single SYSTEM_ADMIN money endpoints  
- **Fix**: Resume S14 before real disbursement rails

### W2-F05 — API-client refresh skips IP allowlist
- **Severity**: Medium · **A6-F05**  
- **Fix**: Re-apply allowlist on refresh for API clients

### W2-F06 — Credential oracle on IP-denied token
- **Severity**: Medium · **A6-F02**  
- **Fix**: Uniform 401; internal audit reason

### W2-F07 — PATCH bypasses LSP payload size guard
- **Severity**: Medium · **A6-F09**  
- **Evidence**: Payload filter POST/PUT only; `LspBorrowerApiController` PATCH  

### W2-F08 — FE OPS_USER permissions overstated vs backend
- **Severity**: Medium · **A5-F09**  
- **Fix**: Align `permissions.ts` with `@PreAuthorize` matrix

### W2-F09 — Dead `app_permission` schema
- **Severity**: Low · **A5-F02 High downgraded**  
- **Rationale**: Unused tables ≠ privilege bypass; coarse RBAC is intentional

### W2-F10 — CSRF disabled on cookie auth endpoints
- **Severity**: Medium · **A4-F03**  
- **Mitigation**: SameSite=Strict  

### Positives
- SPA memory-only access token; same-origin HTTP guard  
- API client kill chain + cache eviction  
- Rate limit fail-closed on Redis errors  
- Auth audit event matrix tests  
- Role/LSP consistency validation  

---

## 16–17. Target & remediation

| When | Item |
|---|---|
| Immediate | W2-F01, W2-F02 session termination correctness |
| 30d | Trusted proxy / XFF; refresh IP check; PATCH payload; FE permission matrix |
| 30d | Uniform token failure responses; refresh-family reuse cascade |
| Before live rails | S14 maker-checker; MFA decision; remove bootstrap default password footguns |

---

## 18. Open questions

- Production reverse-proxy XFF contract?  
- Will entitlements ever replace coarse roles, or delete `app_permission`?  
- MFA required for SYSTEM_ADMIN before go-live?

## 19–21. Evidence / agents

Raw: `W2-A4-auth-RAW.md`, `W2-A5-rbac-RAW.md`, `W2-A6-partner-ratelimit-RAW.md`  
Lead verified High session findings against `AppUser`, `UserAdminService`, `ManagedUserJwtPrincipalResolver`, `AuthTokenService`.
