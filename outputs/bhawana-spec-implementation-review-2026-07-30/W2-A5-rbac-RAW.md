# Wave 2 Agent A5 — User Roles & Permissions Authorization Audit

**Repo:** `/Users/siddhant/Desktop/lms`  
**Spec:** `/Users/siddhant/Desktop/work/ferratum-products-specs-res/areas/bhawana/platform-setup/user-role-and-permission-management/spec.md`  
**Mode:** Read-only  
**Date:** 2026-07-30

---

## 1. Executive Summary

Bhawana LMS implements **pure coarse RBAC**: six fixed roles (`RoleCode`), seeded at startup, enforced almost entirely via Spring `@PreAuthorize("hasRole" / "hasAnyRole")`. There is **no live permission entitlement layer** — `app_permission` / `app_role_permission` exist in Flyway `V1__foundation.sql` but have **zero Java usage**.

**Authorization is real on the backend.** The React SPA adds route guards (`RequireAuth`, `RequireInternal`, `RequireLsp`, `RequireRole`) and a frontend-only `permissions.ts` map; these are **not security boundaries**. A caller with a valid JWT can bypass the UI and hit APIs directly.

**Maker-checker is absent.** A single `SYSTEM_ADMIN` can create other admins, reset passwords, disburse, manually override loan status, and re-sync the bootstrap account — with audit trails but **no second approver, limit, or segregation of duties**.

**Role bootstrap** is config-driven (`SecurityProperties` + `RoleBootstrapService` + `LocalBootstrapAdminSyncService`), defaulting to a bootstrap user with **both `SYSTEM_ADMIN` and `OPS_USER`**.

**Verdict:** Appropriate for a prototype; **not bank-grade** for privileged financial operations or identity administration without maker-checker, fine-grained entitlements, and FE/BE permission parity.

---

## 2. Scope & Method

| Area | Artifacts read |
|------|----------------|
| Spec | `user-role-and-permission-management/spec.md` (v1.0.1) |
| Domain | `RoleCode.java`, `AppRole.java`, `AppUser.java`, `V1__foundation.sql` |
| Security | `SecurityConfig.java`, `SecurityFilterChainConfig.java`, `JwtSecurityBeans.java`, `ManagedUserJwtPrincipalResolver.java`, `SecurityProperties.java`, `AuthenticationTenantScopeFilter.java` |
| User admin | `UserAdminController.java`, `UserAdminService.java`, `RoleBootstrapService.java`, `LocalBootstrapAdminSyncService.java`, `AdminMetadataController.java` |
| `@PreAuthorize` | Full grep across `backend/src/main/java` (all controllers) |
| Frontend | `frontend/src/routes/guards.tsx`, `router.tsx`, `features/users/**`, `lib/permissions.ts`, `lib/role-gates.ts`, loan detail lifecycle UI |
| README roles | Repo `README.md`, `backend/README.md`, spec role table |

---

## 3. Spec Alignment (As-Is)

| Spec claim | Code evidence | Match? |
|------------|---------------|--------|
| Six fixed roles, no runtime role CRUD | `RoleCode` enum (6 values); `RoleBootstrapService` idempotent seed | Yes |
| No permission objects at runtime | No `AppPermission` entity; `@PreAuthorize` only | Yes |
| User admin = `SYSTEM_ADMIN` only | `UserAdminController` class `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` | Yes |
| Role/LSP scope mutual exclusion | `UserAdminService.validateRoleLspConsistency` | Yes |
| Self-disable / last-admin guards | `enforceSelfEditGuards` — `SELF_DISABLE_FORBIDDEN`, `LAST_SYSTEM_ADMIN` | Yes (self only) |
| Session revoke on role change | `sessionRevocationService.revokeAllSessions(..., ROLE_CHANGE)` | Yes |
| Audit on update/reset, not create | `writeUserAuditEvent` on update/reset only; `createUser` has no audit | Yes |
| Gap G-6: no maker-checker | No dual-control code anywhere in backend | Yes (gap confirmed) |
| Gap G-5: temp password in API response | `ResetPasswordResponse.temporaryPassword`; create returns via FE mint + backend flag | Yes |

---

## 4. Six Roles — README / Spec vs Code

Repo root `README.md` does **not** enumerate roles. Roles are documented in the **spec** and **`backend/README.md`** (bootstrap section). Comparison against code:

| Role | Scope (spec) | `RoleCode` | `RoleBootstrapService` description | FE `Role` schema | Bootstrap default |
|------|--------------|------------|-----------------------------------|------------------|-------------------|
| `SYSTEM_ADMIN` | Internal | ✓ | "Full access to all tenants and system controls" | ✓ | ✓ (with `OPS_USER`) |
| `OPS_USER` | Internal | ✓ | "Operational access across loan lifecycle workflows" | ✓ | ✓ (with `SYSTEM_ADMIN`) |
| `PRODUCT_ADMIN` | Internal | ✓ | "Loan product configuration and mapping control" | ✓ | — |
| `LSP_UI_READ` | Partner LSP | ✓ | "Read-only tenant UI access" | ✓ | — |
| `LSP_UI_WRITE` | Partner LSP | ✓ | "Read-write tenant UI access" | ✓ | — |
| `LSP_API_CLIENT` | Partner machine | ✓ | "Machine-to-machine tenant integration access" | ✓ | API-only (not SPA) |

**Finding:** Catalog is **consistent** across enum, seed service, metadata API (`AdminMetadataService` returns `RoleCode.values()`), and frontend Zod schema. **No seventh role** in code.

---

## 5. Permission Model — Entitlements vs Coarse Roles

### 5.1 Backend: coarse roles only

- JWT carries `roles` claim → `ROLE_<code>` authorities (`JwtSecurityBeans.grantedAuthoritiesConverter`).
- Enforcement: `@PreAuthorize` on controllers/methods.
- **No** `hasAuthority('PERM_*')`, no permission table reads.

### 5.2 Orphan schema

`V1__foundation.sql` creates `app_permission` and `app_role_permission`. Grep for `AppPermission` / `app_permission` in Java: **no matches**.

### 5.3 Frontend: synthetic permissions (UI only)

`frontend/src/lib/permissions.ts` maps each role to strings like `LOAN_STATUS_UPDATE`, `DISBURSEMENT_TRIGGER`. Used by lifecycle `gates.ts` / `ActionBar` — **not sent to backend, not in JWT**.

**Conclusion:** Permissions are **documentation/UI hints**, not entitlements. Effective access = **role bundles hardcoded in `@PreAuthorize`**.

---

## 6. Distinct `@PreAuthorize` Expressions (Complete Inventory)

Eight unique expression strings across all Java controllers:

| ID | Expression | Typical controllers / usage |
|----|------------|------------------------------|
| E1 | `hasRole('SYSTEM_ADMIN')` | User admin, LSP admin, reports, audit, webhooks, API clients, IP allowlists, home dashboard, many ops **mutations** |
| E2 | `hasAnyRole('SYSTEM_ADMIN','OPS_USER')` | `LoanApplicationOpsController` (class), `BorrowerAdminController` (class), `OpsAlertController` (class) |
| E3 | `hasAnyRole('SYSTEM_ADMIN','PRODUCT_ADMIN')` | `LoanProductAdminController`, `ProductLspMappingAdminController` |
| E4 | `hasAnyRole('SYSTEM_ADMIN','OPS_USER','PRODUCT_ADMIN')` | `LspOptionsController`, `ProductOptionsController` |
| E5 | `hasAnyRole('SYSTEM_ADMIN','OPS_USER','PRODUCT_ADMIN','LSP_UI_READ','LSP_UI_WRITE')` | `SystemController.context` |
| E6 | `hasAnyRole('LSP_API_CLIENT','LSP_UI_READ','LSP_UI_WRITE')` | Partner read APIs (loans, applications, borrowers, products) |
| E7 | `hasRole('LSP_API_CLIENT')` | Partner create/write-only endpoints (e.g. create application, some loan mutations) |
| E8 | `hasAnyRole('LSP_API_CLIENT','LSP_UI_WRITE')` | Partner write UI paths (schedule update, some application writes) |

**Method-level refinements** (override class defaults):

- `LoanApplicationOpsController`: disbursement, payments, foreclosure, manual status, status-transitions → **E1 only** (SYSTEM_ADMIN).
- `BorrowerAdminController`: `PATCH .../bank-details` → **E1**.
- `OpsAlertController`: `GET /rules` → **E1**; list/ack/escalate → class E2.
- `SystemController`: `POST /bootstrap-sync` → **E1**.

Controllers **without** `@PreAuthorize` (rely on filter chain + authenticated JWT): `AuthController` (login/refresh/logout permitted in filter chain).

---

## 7. Security Filter Chain (`SecurityFilterChainConfig`)

| Rule | Behavior |
|------|----------|
| `permitAll` | `/actuator/health/**`, `/actuator/info`, `/error`, `/api/v1/auth/login`, `/token`, `/refresh`, `/logout` |
| `authenticated` | Swagger/OpenAPI, `/api/v1/auth/password` |
| `/api/v1/internal/system/context` | `authenticated` only (method security E5) |
| `/api/v1/**` | Custom: authenticated **and** NOT `ROLE_PASSWORD_CHANGE_REQUIRED` |
| Password-change gate | Missing authority → 428 `PASSWORD_CHANGE_REQUIRED` on access denied |
| CSRF | Disabled (stateless JWT) |
| Tenant scope | `AuthenticationTenantScopeFilter`: `lspId` claim → tenant DS; else admin scope |

**Evidence:** Coarse URL gate blocks password-change-required users from business APIs; **role checks happen at method level**.

---

## 8. User Administration & Privilege Controls

### 8.1 Surface (`UserAdminController`)

All endpoints under `/api/v1/internal/admin/users` — class-level E1.

| Operation | Guards |
|-----------|--------|
| Create | Username/email uniqueness; role catalog; `validateRoleLspConsistency`; `passwordChangeRequired=true` |
| Update | Non-empty roles; scope rules; **self-edit only** for disable / last-admin |
| Reset password | Temp password; unlock brute-force; session revoke; audit |
| Revoke sessions | Token version bump; refresh revoke; auth audit |

### 8.2 Self-edit vs cross-user privilege

`enforceSelfEditGuards` runs only when `user.getUsername().equalsIgnoreCase(actorUsername)`:

- Cannot self-deactivate.
- Cannot remove own `SYSTEM_ADMIN` if last active admin (excluding self).

**No guard** when admin A edits admin B: A can deactivate B, strip B's `SYSTEM_ADMIN`, or grant B `SYSTEM_ADMIN` — **single actor**.

### 8.3 Create audit gap (spec-intentional)

`createUser` does **not** write `app_user_audit_event`. First audit on that user appears on subsequent update/reset.

### 8.4 Frontend user admin

- Route: `RequireInternal` + `RequireRole(SYSTEM_ADMIN_ONLY)` in `router.tsx`.
- API client mints client-side temp password on create (`users/api.ts`); backend still stores hash and forces change.
- UI assigns **one role** (`roles: [input.role]`) though backend accepts a set.

---

## 9. Role Bootstrap

| Component | Role |
|-----------|------|
| `RoleBootstrapService` | On startup: insert missing rows from fixed `DEFAULT_ROLES` map (6 entries) |
| `LocalBootstrapAdminSyncService` | On startup + on-demand: upsert bootstrap `app_user` from `app.security.bootstrap-user.*` |
| `SecurityProperties` | Default username `ops.admin`, password `ChangeMe123!`, roles **`[SYSTEM_ADMIN, OPS_USER]`** |
| `SecurityConfig.userDetailsService` | Pre-DB fallback: in-memory bootstrap user for login if username not in DB yet |
| `SystemController.bootstrapSync` | E1 — re-heals bootstrap credentials/roles from env |

**Implications:**

- Bootstrap account is **multi-role internal** (allowed by scope rules).
- `POST /bootstrap-sync` lets any SYSTEM_ADMIN reset bootstrap identity from config — powerful recovery, also **privilege concentration**.
- Production profile validation requires non-default JWT secret/password (per `backend/README.md`).

---

## 10. Frontend Authorization Gates

### 10.1 `guards.tsx`

| Guard | Check |
|-------|-------|
| `RequireAuth` | Session + redirect if `mustChangePassword` |
| `RequireInternal` | `isInternalUser` — blocks LSP UI roles |
| `RequireLsp` | `isLspUiUser` — blocks internal roles |
| `RequireRole` | Single `session.user.role` in allow-list |

### 10.2 `router.tsx` route matrix

| Route | FE roles |
|-------|----------|
| `/home` | All authenticated (page redirects non-admin) |
| `/loan-applications`, `/borrowers`, `/alerts` | Internal + `SYSTEM_ADMIN` \| `OPS_USER` |
| `/reports`, `/lsps`, `/users`, `/api-clients`, `/audit` | Internal + `SYSTEM_ADMIN` only |
| `/products` | Internal + `SYSTEM_ADMIN` \| `PRODUCT_ADMIN` |
| `/my-loans` | LSP + `LSP_UI_READ` \| `LSP_UI_WRITE` |

`LSP_API_CLIENT` is **not** in SPA role constants — correct (M2M only).

### 10.3 Session role selection

`auth-service.ts` and `users/api.ts` use **priority pick** (SYSTEM_ADMIN > OPS_USER > …) when JWT has multiple roles. UI exposes **one** effective role.

### 10.4 Loan lifecycle UI (important divergence)

`DetailHeader.tsx`: **`OPS_USER` does not get `ActionBar`** — only "Escalate to admin". `SYSTEM_ADMIN` gets full `ActionBar` + `ForeclosureQuotePanel`.

This **mitigates** FE `permissions.ts` granting `OPS_USER` `LOAN_STATUS_UPDATE` / disbursement permissions, but those permissions remain **misleading** for any other UI surface using `hasPermission`.

---

## 11. FE vs BE Authorization Divergence

| Capability | FE (`permissions.ts` / UI) | BE (`@PreAuthorize`) | Risk |
|------------|------------------------------|----------------------|------|
| Ops status transition | OPS_USER: `LOAN_STATUS_UPDATE`; UI hides ActionBar for OPS | POST `status-transitions` → E1 | Low API risk; FE map wrong |
| Disbursement | OPS_USER: `DISBURSEMENT_TRIGGER` in map; UI hidden | POST `disbursement-requests` → E1 | Low API risk |
| Record payment | `canPostRepayment` allows OPS | POST `payments` → E1 | **Medium** — detail page may show repayment affordances for OPS |
| Manual status override | `api-detail.ts` SYSTEM_ADMIN check | E1 | Aligned |
| Product config | Router allows PRODUCT_ADMIN | E3 on product controllers | Aligned |
| User admin | Router SYSTEM_ADMIN only | E1 | Aligned |
| Home dashboard KPIs | Page redirects non-admin; router allows all | `HomeDashboardController` E1 | Low — API 403 if called |
| LSP create application | No SPA path | POST create → E7 `LSP_API_CLIENT` only | Aligned (ADR 0003) |

**Net:** Backend is stricter for money/status mutations. Frontend gates are **UX**; **`permissions.ts` overstates OPS_USER** relative to API.

---

## 12. Tenant Isolation (D8)

| Layer | Mechanism |
|-------|-----------|
| Assignment | LSP roles require `lspId`; internal roles forbid LSP |
| JWT | `lspId`, `lspName` claims for LSP users (`AuthTokenService.loadManagedUserClaims`) |
| Request | `AuthenticationTenantScopeFilter` → tenant or admin datasource |
| Partner APIs | RLS / tenant routing (tenant package) |

Internal roles (`SYSTEM_ADMIN`, `OPS_USER`, `PRODUCT_ADMIN`) operate in **admin scope** (cross-tenant). This matches spec "internal = cross-tenant".

---

## 13. Session & Token Lifecycle (AuthZ-relevant)

| Control | Implementation |
|---------|----------------|
| Role change | `token_version` bump + refresh revoke + `AuthPrincipalCache` eviction |
| Password reset (admin) | Same + `ADMIN_RESET_PASSWORD` |
| Explicit revoke | `ADMIN_EXPLICIT` |
| JWT validation | `tv` and `pwdv` claims vs DB (`ManagedUserJwtPrincipalResolver`) |
| Password change required | `ROLE_PASSWORD_CHANGE_REQUIRED` blocks `/api/v1/**` except password + context |

Effective revocation is **next request**, not TTL — matches spec NFR-005.

---

## 14. Maker-Checker & Privilege Escalation Assessment

| Control | Present? |
|---------|----------|
| Dual approval for disbursement | **No** — single SYSTEM_ADMIN |
| Dual approval for manual status | **No** |
| Dual approval for granting SYSTEM_ADMIN | **No** |
| Delegated user admin (non-SYSTEM_ADMIN) | **No** |
| Limit on admin actions per day / per principal | **No** |
| Step-up auth for privileged ops | **No** |
| Prevent last admin lockout (cross-user) | **No** — only self LAST_SYSTEM_ADMIN |
| Audit on privileged changes | **Partial** — update/reset/session revoke; not create |
| Bootstrap sync by any SYSTEM_ADMIN | **Yes** — can restore env-defined super-user |

**Privilege escalation paths (evidence-based):**

1. SYSTEM_ADMIN creates user with `roles: [SYSTEM_ADMIN]` — no second approver.
2. SYSTEM_ADMIN updates any user to add `SYSTEM_ADMIN`.
3. SYSTEM_ADMIN calls `bootstrap-sync` — resets bootstrap account from env.
4. Config bootstrap defaults include **SYSTEM_ADMIN + OPS_USER** on one identity.
5. Multi-role JWT: all roles in claim become authorities; `hasAnyRole` passes if **any** assigned role matches.

---

## 15. Findings Register

| ID | Severity | Finding | Evidence |
|----|----------|---------|----------|
| **W2-A5-F01** | Info | **Pure coarse RBAC** — access = role enum + `@PreAuthorize`; no live permission entitlements | `RoleCode.java`; no `AppPermission`; spec §Roles & Permission Model |
| **W2-A5-F02** | High | **`app_permission` schema is dead** — tables exist, never read or seeded in app code | `V1__foundation.sql` L19–48; Java grep empty |
| **W2-A5-F03** | Critical | **No maker-checker** for disbursement, manual status, foreclosure, or identity admin | Spec gap G-6; `LoanApplicationOpsController` mutations E1; `UserAdminController` E1 |
| **W2-A5-F04** | High | **Single SYSTEM_ADMIN can grant SYSTEM_ADMIN** with no dual control or approval workflow | `UserAdminService.createUser` / `updateUser`; no cross-user last-admin guard |
| **W2-A5-F05** | Medium | **LAST_SYSTEM_ADMIN guard is self-only** — does not prevent two admins from leaving one admin then removing them | `enforceSelfEditGuards` only when actor == target |
| **W2-A5-F06** | Medium | **Bootstrap default is dual-role** (`SYSTEM_ADMIN` + `OPS_USER`) on one account | `SecurityProperties.DEFAULT_BOOTSTRAP_ROLES`; `LocalBootstrapAdminSyncService` |
| **W2-A5-F07** | Medium | **`bootstrap-sync` allows any SYSTEM_ADMIN to re-heal bootstrap super-user from env** | `SystemController.bootstrapSync` E1 |
| **W2-A5-F08** | Medium | **FE route guards are not security boundaries** — APIs enforce E1–E8 | `guards.tsx` vs controller annotations |
| **W2-A5-F09** | Medium | **`permissions.ts` overstates OPS_USER** vs backend (status/disbursement E1) | `permissions.ts` OPS_USER_PERMS; `LoanApplicationOpsController` |
| **W2-A5-F10** | Low | **Loan detail mitigates OPS_USER** by hiding ActionBar, but repayment/other surfaces may still use `canPostRepayment` | `DetailHeader.tsx` L128–169 vs `canPostRepayment` |
| **W2-A5-F11** | Medium | **FE single-role model** collapses multi-role JWT; priority pick may hide effective role for UI | `auth-service.ts` `selectPrimaryRole`; bootstrap multi-role |
| **W2-A5-F12** | Low | **User create not audited** (spec FR-007) — initial privilege grant leaves no `app_user_audit_event` | `UserAdminService.createUser` — no `writeUserAuditEvent` |
| **W2-A5-F13** | Info | **LSP origination POST is API-client-only** — UI read/write roles cannot create applications via API | `LspLoanApplicationApiController` E7; ADR 0003 |
| **W2-A5-F14** | Info | **Role/LSP scope enforcement at assignment is sound** — internal/LSP mixing blocked | `validateRoleLspConsistency` |
| **W2-A5-F15** | Info | **Session invalidation on role change is implemented** — `tv` / refresh revoke / cache eviction | `UserAdminService.updateUser` + `ManagedUserJwtPrincipalResolver` |

---

## Appendix A — Role → Representative Backend Access

| Role | Read-heavy | Write / privileged |
|------|------------|-------------------|
| SYSTEM_ADMIN | All admin/ops surfaces | Everything E1–E5 |
| OPS_USER | Ops loans, borrowers (E2), alerts (partial) | **No** disbursement/payment/foreclosure/manual status (E1 overrides) |
| PRODUCT_ADMIN | Products, mappings (E3), options (E4) | Product config only |
| LSP_UI_READ | Partner reads (E6) | No writes |
| LSP_UI_WRITE | Partner reads (E6) | Partner writes (E8); not create app (E7) |
| LSP_API_CLIENT | Partner reads (E6) | Create app + API writes (E7/E8) |

---

## Appendix B — Key File Paths

- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/RoleCode.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/AppRole.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/domain/AppUser.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityConfig.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/security/SecurityFilterChainConfig.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/web/UserAdminController.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/UserAdminService.java`
- `/Users/siddhant/Desktop/lms/backend/src/main/java/com/bhawana/lms/service/RoleBootstrapService.java`
- `/Users/siddhant/Desktop/lms/frontend/src/routes/guards.tsx`
- `/Users/siddhant/Desktop/lms/frontend/src/routes/router.tsx`
- `/Users/siddhant/Desktop/lms/frontend/src/lib/permissions.ts`
- `/Users/siddhant/Desktop/lms/frontend/src/features/users/api.ts`

[REDACTED]