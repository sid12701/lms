# ADR 0002: LSP disable kill chain (issue #63)

## Status

Accepted — implemented.

## Context

GitHub issue **#63** required a real admin path to disable an LSP such that:

- Outstanding LSP and API-client JWTs are invalidated immediately.
- API clients under the tenant are deactivated.
- Operators record a **reason** and **audit note**.
- Status changes are visible in admin UI and queryable afterward.

Previously, `frontend-2` called `PUT …/status` with hardcoded `OPERATIONAL` / generic notes, or (in stale bundles) never called the status API at all.

## Decision

### Backend

1. **Flyway V77** — `lsp.token_version`, `api_client.token_version`, table `lsp_audit_event`.
2. **`PUT /api/v1/internal/admin/lsps/{lspId}/status`** — body: `status` (`ACTIVE` | `INACTIVE` | `DISABLED`), `reason`, `note` (all required on change).
3. **`GET /api/v1/internal/admin/lsps/{lspId}/audit-events`** — status-change audit trail.
4. **`LspStatusService`** — disable bumps token versions, cascades client deactivation, writes audit, emits `LSP_DISABLED` alert; reactivate sets LSP `ACTIVE` only (clients need secret rotation).
5. **JWT validation** — `ApiClientJwtSessionValidator` enforces `tvLsp` / `tvApiClient` and inactive statuses (`LSP_TOKEN_REVOKED`, `LSP_INACTIVE`, `API_CLIENT_INACTIVE`, `API_CLIENT_TOKEN_REVOKED`).
6. **No-op guard** — same target status → `400 STATUS_UNCHANGED` (no audit row).

### Frontend (`frontend-2`)

1. **`LspStatusChangeDialog`** — reason picker, required note, disable/reactivate warnings.
2. **`LspAuditEventsDialog`** — reads `GET …/audit-events`.
3. **`LspDetailsDialog`** — read-only summary; links to Status / Audit.
4. Table actions: **Details · Status · Audit · Webhook** (removed misleading **Edit** flow).
5. List/audit cache: no GET dedupe for admin reads, immediate cache patch + refetch after status change; audit dialog opens after success.

## Consequences

- Operators must run backend and `frontend-2` from a build that includes this ADR; old backend returns **404** on `PUT …/status`.
- Database must be at **Flyway v77+** on the instance the backend uses.
- Reactivate does not auto-enable API clients; use API client secret rotation when parent LSP is `ACTIVE`.

## References

- `LspAdminController`, `LspStatusService`, `LspStatusKillChainIntegrationTest`
- `frontend-2/src/features/lsps/`
