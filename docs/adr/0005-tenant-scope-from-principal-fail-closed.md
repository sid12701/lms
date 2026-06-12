# ADR 0005 — Tenant data-access scope is derived from the authenticated principal and fails closed

- **Status:** Accepted (2026-06-11)
- **Drives:** prod 401-loop regression (#89 follow-up), E2E finding UC-055 (alert scheduler `MissingTenantContextException`)
- **Related:** #89/#146 ([B-7] holder defaulted to ADMIN — closed by PR #182, which made the holder throw), ADR 0001 (frontend integration model)

## Context

PR #182 (#89) made `TenantDataAccessContextHolder` fail closed: any data access without an explicit admin/tenant scope throws `MissingTenantContextException` at `TenantRoutingDataSource`. Scope was provisioned per-request by two MVC interceptors keyed on path patterns (`/api/v1/internal/**`, `/api/v1/auth/**` → admin; `/api/v1/lsp/**` → tenant from the `lspId` JWT claim), and per-worker by `TenantScopedExecution`.

This left a structural gap: the security filter chain runs **before** MVC interceptors, and JWT session validation does repository lookups (`AppUserRepository`, `ApiClientRepository`). In the `test` profile a restore filter masked the gap; in prod every authenticated request failed token validation and 401'd (the #89 regression). An interim uncommitted fix added a blanket filter that seeded **admin** scope for every request when none was present — which silently reintroduced the implicit-admin default that #89 was raised to eliminate: any path missing the narrowing interceptor (unmapped URLs, error/async dispatches, actuator, future endpoints) would run with admin data access instead of failing.

## Decision

1. **Scope follows the principal, set in the security filter chain.** `AuthenticationTenantScopeFilter` runs immediately after `BearerTokenAuthenticationFilter`: principals carrying an `lspId` claim get **tenant** scope; all other authenticated principals get **admin** scope; anonymous requests are left **unscoped**. The previous scope is snapshot/restored around the request.
2. **Unscoped data access fails closed, loudly.** `MissingTenantContextException` keeps throwing at the routing datasource and now maps to a dedicated 500 response (`TENANT_SCOPE_MISSING`), an error log, and a `lms.tenant.scope.missing` metric. It is a server invariant violation, never a client error, and never a silent admin fallback.
3. **Pre-authentication and perimeter lookups use explicit, narrowly-scoped system context.** Token validators, the granted-authorities converter, the login `UserDetailsService`, and the LSP surface IP-allowlist filter (which reads admin-owned config while the request holds tenant scope) wrap only their repository calls in `TenantScopedExecution.callAsAdmin/runAsAdmin`. These are principal-resolution and perimeter-enforcement operations and are cross-tenant by nature. The Postgres RLS integration test caught exactly this: the tenant DB role has no grant on `lsp_api_ip_allowlist`, so an unwrapped lookup fails loudly instead of leaking.
4. **Anonymous auth endpoints keep interceptor-provided admin scope.** `InternalAdminTenantContextInterceptor` now covers only `/api/v1/auth/**` (login, token, refresh, logout, password) — endpoints that legitimately resolve principals before authentication exists. The `/api/v1/internal/**` pattern is removed: authenticated internal traffic is scoped by the filter, so a path-pattern drift can no longer grant admin scope by location rather than identity.
5. **`LspTenantContextInterceptor` is retained as defense-in-depth.** It re-asserts tenant scope and rejects non-LSP principals on `/api/v1/lsp/**`; it is no longer the primary scope provisioner.

## Consequences

- A request that reaches business code without a resolved scope is a bug and surfaces as an alertable 500, not as cross-tenant data exposure. Availability failure is the accepted trade for confidentiality safety.
- An LSP principal calling any endpoint — including internal ones — only ever holds tenant scope; authorization rejections (`@PreAuthorize`) happen with the narrow scope already in place.
- Workers and async tasks are unchanged: ThreadLocal scope does not propagate, so every entry point must use `TenantScopedExecution` (the fail-closed datasource enforces this).
- Long-term defense-in-depth (Postgres RLS or Hibernate tenant filters keyed on the connection scope) remains open as a separate decision; this ADR only fixes scope provisioning.
