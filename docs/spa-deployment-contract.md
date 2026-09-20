# M15 SPA deployment contract (required static-host/ingress behavior)

The operations SPA is a separately built static bundle (Vite/React). The backend
owns **no** SPA origin implicitly: every cross-origin behavior below is explicit
deployment configuration. Anything not configured fails closed.

## 1. CORS allowlist is deployment config

Browser origins allowed to call the API **with credentials** come from
`app.security.cors.allowed-origins` (env `APP_SECURITY_CORS_ALLOWED_ORIGINS`,
comma-separated explicit origins, e.g.
`APP_SECURITY_CORS_ALLOWED_ORIGINS=https://app.example.com,https://ops.example.com`).

- **Empty (the base `application.yml` default) means none** — no cross-origin
  browser call is allowed. That is the correct posture for API-only deployments
  and for a deployment that forgot to configure its SPA origin.
- The localhost dev-server origins (`http://localhost:5173`,
  `http://127.0.0.1:5173`, `http://localhost:4200`, `http://127.0.0.1:4200`)
  live **only** in `application-local.yml` and load only when the `local`
  profile is explicitly active. No other profile inherits them.
- `allowCredentials=true` always — origins must be explicit. A wildcard entry
  (`*`) is rejected at startup, never silently honored.
- Allowed request headers: `Authorization`, `Content-Type`, `Idempotency-Key`.
- Exposed response headers (what a cross-origin SPA may read):
  `X-Correlation-Id`, `Content-Disposition`, `Retry-After`, `X-Total-Count`,
  `X-Limit`, `X-Offset`. `Retry-After` is the retry contract emitted on `429`
  rate-limit and `409 IDEMPOTENCY_IN_PROGRESS` responses; without exposure the
  browser hides it from fetch.

## 2. Prefer same-site topology — the refresh cookie depends on it

The SPA points at the API via `VITE_API_BASE_URL`. The refresh cookie is
`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, so the browser sends it
only to the API's own **site**:

- **Same-origin** (edge routes `/api/*` to the backend on the SPA's origin):
  simplest; no CORS is exercised at all and the allowlist may stay empty.
- **Same-site cross-origin** (e.g. `app.example.com` + `api.example.com`):
  works — the `Strict` cookie still flows because both share the registrable
  site — provided the SPA origin is in the CORS allowlist.
- **Cross-site** (SPA on a different registrable domain): the browser will
  never send the refresh cookie, so sessions cannot renew. Do **not** loosen
  `SameSite` or add a wildcard credentialed origin to disguise that failure —
  fix the topology instead.

## 3. The SPA's CSP is the static host's job — do not copy the backend's

The backend emits `Content-Security-Policy: default-src 'none'; frame-ancestors
'none'` on **API responses**. That policy does not apply to, and must not be
copied onto, separately hosted SPA HTML — `default-src 'none'` would break the
bundle outright. The static host / ingress serving the SPA MUST set its own
document policy, for example:

```
Content-Security-Policy: default-src 'self'; connect-src 'self' https://api.example.com; frame-ancestors 'none'
```

with `connect-src` naming the configured API origin and further directives
extended only as the bundle measurably requires.

## 4. Vite `base` only for a real subpath

Vite's default root base (`/`) is correct when the SPA is served at the domain
root. Set `base` (build flag or `frontend/vite.config.ts`) only when the SPA is
genuinely served under a subpath; setting it for a root deployment breaks asset
URLs.
