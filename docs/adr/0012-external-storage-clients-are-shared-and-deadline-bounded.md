# ADR 0012 — External storage clients are shared, deadline-bounded, and never closed per call

- **Status:** Accepted (2026-09-20)
- **Drives:** audit finding M05 — storage clients were recreated per call with no request-time budget
- **Related:** H24 (deterministic report output keys), the transactional/external-effect contract (database transactions are not external transactions)

## Context

`R2LoanDocumentStorageService` and `R2ReportStorageService` each built a new `S3Client` per operation and closed it at the end of the call. Every request paid for a fresh connection pool and TLS handshake, downloads had to wrap the stream so closing it also closed the request-scoped client, and no call carried an explicit deadline — a stalled or unreachable endpoint could park a request thread indefinitely. Report downloads additionally buffered the whole export into `byte[]`.

## Decision

1. **One lazily-built client per storage area, shared across calls.** Each service builds its `S3Client` once under a lock on first use and keeps it for the bean's life. Laziness matters: in LOCAL-provider deployments the bean exists but R2 is never configured, so no pool or credentials are materialised.
2. **Clients are never merged across credential sets.** `R2S3ClientFactory` is the single construction point for pool sizing and deadlines, but each service builds from its own `app.storage.*.r2.*` block — documents and reports could legitimately point at different endpoints/credentials and must never silently share a global client.
3. **Every call carries an explicit budget.** `apiCallTimeout` (total, retries included), `apiCallAttemptTimeout` (single attempt), `connectionTimeout`, `socketTimeout`, and `maxConnections` are bound from configuration (`api-call-timeout: PT120S`, `api-call-attempt-timeout: PT30S`, `connection-timeout: PT5S`, `socket-timeout: PT30S`, `max-connections: 50` — sized generously for ≤10 MB documents; the call budget is the knob to raise if measured export sizes outgrow it).
4. **Closing a returned stream releases only its pooled connection.** Callers close `RetrievedDocumentStream#content()` / `ReportStream#content()`; shutdown closes the client exactly once via `@PreDestroy`. Calls after shutdown fail instead of rebuilding a client.
5. **Report downloads stream end to end.** `ReportStorageService.openStream` returns a bounded-memory handle; `ReportRequestService.openCompletedReportDownload` is intentionally not transactional — the object stream must never be opened while a pooled database connection is held — and the controller copies the stream to the response.

## Consequences

- Concurrent store/open/delete calls reuse one bounded pool; a slow or dead endpoint fails inside the configured deadline rather than hanging a request thread.
- The byte-array `retrieve`/`getCompletedReport` paths remain for callers that genuinely need bytes; new download paths use the streaming handle.
- The previous architecture-test debt for `ReportRequestService#getCompletedReport`/`getCompletedReportDownload` (a readOnly transaction spanning a storage fetch) is removed; the only remaining allowlisted debt is `LoanApplicationServicingReadService#downloadDocumentZip`.
