package com.bhawana.lms.repo;

import java.util.UUID;

/**
 * C03 — atomic claim fence token.
 *
 * <p>Issued by the single conditional claim primitive (fast single + batch) and checked at
 * submission preparation. {@code attemptCount} is the existing {@code attempt_count} column used
 * as a monotonically incremented fence; {@code owner} identifies the claiming process
 * (host + startup UUID). Threads in the same process share the owner and must still pass the
 * attempt/state checks.
 */
public record ClaimToken(UUID intentId, String owner, int attemptCount) {
}
