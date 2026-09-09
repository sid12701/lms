package com.bhawana.lms.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * H27 — operational gauges for disbursement work that must be reconciled, never reissued. Counts
 * and oldest ages come from {@link DisbursementVisibilityQueries} bounded aggregates; ages use the
 * stable creation timestamps so polls and retries cannot reset the clock. Labels carry only the
 * bucket — never borrower, account, or request identifiers.
 *
 * <p>All gauges use {@code strongReference(true)}: Micrometer otherwise holds the observed state
 * object weakly, and the per-bucket suppliers here are referenced nowhere else, so a GC cycle
 * would silently flip every gauge to {@code NaN} on scrape.
 */
@Component
public class DisbursementIntentMetrics {

    private final DisbursementVisibilityQueries visibilityQueries;

    public DisbursementIntentMetrics(DisbursementVisibilityQueries visibilityQueries, MeterRegistry meterRegistry) {
        this.visibilityQueries = visibilityQueries;
        registerCount(meterRegistry, "lms.disbursement.intent.pending.count",
                "Live CREATED/REQUESTED intents awaiting execution or a terminal result",
                () -> visibilityQueries.pendingIntents());
        registerAge(meterRegistry, "lms.disbursement.intent.pending.oldest_age_seconds",
                "Age in seconds of the oldest live CREATED/REQUESTED intent",
                () -> visibilityQueries.pendingIntents());
        registerCount(meterRegistry, "lms.disbursement.intent.unknown.count",
                "Number of disbursement intents awaiting provider reconciliation",
                () -> visibilityQueries.unknownIntents());
        registerAge(meterRegistry, "lms.disbursement.intent.unknown.oldest_age_seconds",
                "Age in seconds of the oldest disbursement intent awaiting reconciliation",
                () -> visibilityQueries.unknownIntents());
        registerCount(meterRegistry, "lms.disbursement.intent.unapplied.count",
                "Terminal SUCCEEDED/FAILED intents still joined to a DISBURSEMENT_REQUESTED account",
                () -> visibilityQueries.unappliedTerminalIntents());
        registerAge(meterRegistry, "lms.disbursement.intent.unapplied.oldest_age_seconds",
                "Age in seconds of the oldest unapplied terminal intent",
                () -> visibilityQueries.unappliedTerminalIntents());
        registerCount(meterRegistry, "lms.disbursement.account.parked_without_intent.count",
                "DISBURSEMENT_REQUESTED/PENDING_RECONCILIATION accounts with no live intent",
                () -> visibilityQueries.parkedAccountsWithoutLiveIntent());
        registerAge(meterRegistry, "lms.disbursement.account.parked_without_intent.oldest_age_seconds",
                "Age in seconds of the oldest account with no live intent",
                () -> visibilityQueries.parkedAccountsWithoutLiveIntent());
    }

    private void registerCount(
            MeterRegistry meterRegistry,
            String name,
            String description,
            Supplier<DisbursementVisibilityQueries.BucketSnapshot> snapshot
    ) {
        Gauge.builder(name, snapshot, current -> (double) current.get().count())
                .description(description)
                .strongReference(true)
                .register(meterRegistry);
    }

    private void registerAge(
            MeterRegistry meterRegistry,
            String name,
            String description,
            Supplier<DisbursementVisibilityQueries.BucketSnapshot> snapshot
    ) {
        Gauge.builder(name, snapshot, current -> ageSeconds(current.get().oldestCreatedAt()))
                .description(description)
                .baseUnit("seconds")
                .strongReference(true)
                .register(meterRegistry);
    }

    private static double ageSeconds(Instant oldestCreatedAt) {
        if (oldestCreatedAt == null) {
            return 0;
        }
        return Math.max(0L, Duration.between(oldestCreatedAt, Instant.now()).toSeconds());
    }
}
