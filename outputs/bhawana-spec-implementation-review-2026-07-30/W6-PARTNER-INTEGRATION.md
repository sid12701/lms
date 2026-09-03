# W6 — Partner Integration & Reporting (Verified)

**Agents**: [W6](4cd92663-1f67-4b4e-a7c1-dce091df2929) · Lead spot-checked SSRF client, PAN serializers, report PROCESSING

## Assessment
Webhooks are **deliberately strong** (HMAC, retries, redrive audit, SSRF validator). LSP loan isolation is **generally sound**. MIS masking exists but lifecycle/ops resilience is weak. PII on LSP loan APIs still returns raw PAN (deferred S15).

## Verified High findings
| ID | Finding | Evidence |
|---|---|---|
| W6-F01 | Redirect/SSRF residual risk | `HttpWebhookDeliveryClient` — confirm redirect policy; agent reports follow-bypass |
| W6-F02 | DNS TOCTOU on SSRF check | Validator resolves then connects separately |
| W6-F03 | Stuck MIS `PROCESSING` no reclaim | `ReportRequest` status model; no TTL reclaim found |
| W6-F04 | Batch report work in one large txn | Worker/service transactional boundary |
| W6-F05 | No MIS R2 retention/purge | Storage service lacks lifecycle |
| W6-F06 | Raw PAN on LSP loan list/detail | `LspLoanApplicationResponses` 39, 102 |
| W6-F07 | Shared-borrower bank reveal beyond loan | Borrower visibility model |

## Positives
HMAC signing, soft-4xx/redrive tests, tenant 404 on cross-LSP loans, partner responses omit storage keys.
