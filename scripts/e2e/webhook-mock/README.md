# Webhook mock server (Phase 5)

Minimal HTTP server for EC-062, EC-063 webhook delivery tests.

## Start

```bash
python scripts/e2e/webhook-mock/server.py
```

Listens on `http://127.0.0.1:9090`.

| Path | Behavior |
|------|----------|
| `POST /hook` | 200 + JSON `{"ok":true}` |
| `POST /status/500` | 500 |
| `POST /status/404` | 404 |

## Subscribe in LMS

```http
PUT /api/v1/internal/admin/lsps/{lspId}/webhook-subscription
{
  "endpointUrl": "http://127.0.0.1:9090/hook",
  "subscribedEvents": ["LOAN_STATUS_CHANGED"]
}
```

Then trigger lifecycle events and `POST /admin/webhook-outbox/dispatch`.

**Note:** SSRF tests (EC-066) use subscription-time validation — `169.254.169.254` must be rejected without dispatch.
