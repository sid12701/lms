# ADR 0013 — Rate-limit store outage policy is per-route: fail closed on credential endpoints, fail open elsewhere

- **Status:** Accepted (2026-09-20)
- **Drives:** audit finding M10 — the rate limiter ignored Redis credentials/TLS/timeouts, died with the context when Redis was absent at startup, and had no explicit outage policy
- **Related:** ADR 0005 (fail-closed posture for security-sensitive boundaries)

## Context

The distributed rate limiter hand-rolled `RedisClient.create("redis://host:port")`, discarding every `spring.data.redis.*` setting — credentials, TLS, connect/command timeouts. It connected eagerly at context startup, so an absent Redis failed the whole deployment, and once connected there was no answer to "what happens when the store fails mid-request": the choice between silently admitting unlimited traffic and hard-failing every request was left implicit.

## Decision

1. **The limiter borrows the auto-configured `LettuceConnectionFactory`'s native client.** Username/password, database, TLS (`spring.data.redis.ssl.*`), `connect-timeout`, and the `timeout` command deadline all apply to rate limiting because Spring's validated Redis machinery owns the client. There is no second hand-rolled client.
2. **Connections are lazy and self-healing.** `RateLimitRedisConnectionProvider` dials on first rate-limited request and retries on a `reconnect-interval` cooldown (default 5 s) while the store is unreachable; Lettuce auto-reconnect covers mid-run connection loss. Recovery never requires an instance restart.
3. **Outage policy is per-rule, explicit, and counted.** Each rule declares `on-store-failure`:
   - `FAIL_CLOSED` — login, token, refresh, and password routes answer a bounded `503` with `Retry-After` (default 30 s). These are credential-attack-sensitive: while the limiter is blind they must not accept unlimited attempts.
   - `FAIL_OPEN` — all other matched routes (LSP writes/feed, admin password/session operations, document reads, reports, mock outcome) let the request through. They are already authenticated and authorized; a Redis blip must not take the whole API down.
   - Both paths increment `lms.rate_limit.store.failures` (tagged by rule and policy) and log at most once a minute.
4. **The command deadline is 200 ms.** Bucket4j CAS does a couple of EVALSHA round-trips; measured p99 against a same-region Redis is single-digit milliseconds, so 200 ms is a generous bound that still fails a stalled store before the caller's HTTP budget is spent. It is configurable via `LMS_REDIS_COMMAND_TIMEOUT` and should only be raised with measured latency evidence.
5. **This is an availability control, not a financial one.** The policy is deliberately independent of the idempotency/locking machinery; rate-limit failure modes never touch financial-operation semantics.

## Consequences

- During a Redis outage, credential endpoints degrade to a bounded, honest 503 while the rest of the API stays up — a documented trade, not an accident.
- A misconfigured deployment fails loudly (wrong credentials/TLS → immediate empty store → policy applied), and metrics make the failure observable rather than silent.
- New credential-bearing routes added to `app.rate-limit.rules` must declare `on-store-failure: FAIL_CLOSED`; the default is `FAIL_OPEN`, so omitting it is a conscious availability choice.
