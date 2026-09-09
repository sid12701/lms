package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisbursementIntentMetricsTest {

    @Test
    void exposesUnknownIntentCountAndOldestAge() {
        DisbursementVisibilityQueries queries = mock(DisbursementVisibilityQueries.class);
        when(queries.unknownIntents()).thenReturn(
                new DisbursementVisibilityQueries.BucketSnapshot(3, Instant.now().minusSeconds(120)));
        when(queries.pendingIntents()).thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        when(queries.unappliedTerminalIntents()).thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        when(queries.parkedAccountsWithoutLiveIntent())
                .thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DisbursementIntentMetrics(queries, registry);

        assertEquals(3.0, registry.get("lms.disbursement.intent.unknown.count").gauge().value());
        double age = registry.get("lms.disbursement.intent.unknown.oldest_age_seconds").gauge().value();
        assertTrue(age >= 120 && age < 125);
    }

    @Test
    void exposesPendingUnappliedAndParkedBucketsWithZeroWhenEmpty() {
        DisbursementVisibilityQueries queries = mock(DisbursementVisibilityQueries.class);
        when(queries.pendingIntents()).thenReturn(
                new DisbursementVisibilityQueries.BucketSnapshot(2, Instant.now().minusSeconds(60)));
        when(queries.unknownIntents()).thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        when(queries.unappliedTerminalIntents()).thenReturn(
                new DisbursementVisibilityQueries.BucketSnapshot(1, Instant.now().minusSeconds(300)));
        when(queries.parkedAccountsWithoutLiveIntent())
                .thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new DisbursementIntentMetrics(queries, registry);

        assertEquals(2.0, registry.get("lms.disbursement.intent.pending.count").gauge().value());
        double pendingAge = registry.get("lms.disbursement.intent.pending.oldest_age_seconds").gauge().value();
        assertTrue(pendingAge >= 60 && pendingAge < 65);
        assertEquals(1.0, registry.get("lms.disbursement.intent.unapplied.count").gauge().value());
        assertEquals(0.0, registry.get("lms.disbursement.account.parked_without_intent.count").gauge().value());
        assertEquals(0.0,
                registry.get("lms.disbursement.account.parked_without_intent.oldest_age_seconds").gauge().value());
    }

    @Test
    void gaugesStayMeaningfulAfterGarbageCollection() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registerMetricsWithoutRetainingThem(registry);
        forceGarbageCollection();

        // Without strong references the observed suppliers would be collected and every gauge
        // would read NaN here (and scrape NaN). The meters must survive on the registry alone.
        assertEquals(3.0, registry.get("lms.disbursement.intent.unknown.count").gauge().value());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("lms_disbursement_intent_unknown_count 3.0"),
                "Prometheus scrape must carry the gauge value after GC, got:\n" + scrape);
    }

    private static void registerMetricsWithoutRetainingThem(PrometheusMeterRegistry registry) {
        DisbursementVisibilityQueries queries = mock(DisbursementVisibilityQueries.class);
        when(queries.unknownIntents()).thenReturn(
                new DisbursementVisibilityQueries.BucketSnapshot(3, Instant.now().minusSeconds(120)));
        when(queries.pendingIntents()).thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        when(queries.unappliedTerminalIntents()).thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        when(queries.parkedAccountsWithoutLiveIntent())
                .thenReturn(DisbursementVisibilityQueries.BucketSnapshot.empty());
        new DisbursementIntentMetrics(queries, registry);
    }

    private static void forceGarbageCollection() {
        List<byte[]> garbage = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            garbage.add(new byte[4 * 1024 * 1024]);
            System.gc();
        }
        garbage.clear();
        System.gc();
    }
}
