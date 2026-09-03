package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The stuck-disbursement alert message previously read
 * "has been in DISBURSEMENT_RETRY since 2026-08-06T16:25:22.311456Z" — a raw backend enum and a
 * raw {@link Instant#toString()} in copy an operator reads on the alerts queue.
 *
 * It went unnoticed for the life of the product because the rule can only fire against a loan in
 * DISBURSEMENT_RETRY, and no such row had ever existed in a dev database. It was found the first
 * time one was deliberately created.
 */
class AlertRuleEvaluationWorkerStuckDisbursementCopyTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-08-06T18:00:00Z");

    @Test
    void singleHourIsNotPluralised() {
        assertEquals("1 hour", AlertRuleEvaluationWorker.describeStuckDuration(EVALUATED_AT.minus(Duration.ofHours(1)), EVALUATED_AT));
    }

    @Test
    void multipleHoursArePluralised() {
        assertEquals("6 hours", AlertRuleEvaluationWorker.describeStuckDuration(EVALUATED_AT.minus(Duration.ofHours(6)), EVALUATED_AT));
    }

    @Test
    void underAnHourDegradesToMinutesRatherThanZeroHours() {
        assertEquals("1 minute", AlertRuleEvaluationWorker.describeStuckDuration(EVALUATED_AT.minus(Duration.ofSeconds(90)), EVALUATED_AT));
        assertEquals("42 minutes", AlertRuleEvaluationWorker.describeStuckDuration(EVALUATED_AT.minus(Duration.ofMinutes(42)), EVALUATED_AT));
    }

    @Test
    void aRetryInstantThatNeverLandedDoesNotRenderZeroOrNull() {
        assertEquals("some time", AlertRuleEvaluationWorker.describeStuckDuration(null, EVALUATED_AT));
    }

    @Test
    void copyNeverCarriesARawEnumOrAnIsoInstant() {
        String rendered = AlertRuleEvaluationWorker.describeStuckDuration(EVALUATED_AT.minus(Duration.ofHours(3)), EVALUATED_AT);
        assertFalse(rendered.contains("DISBURSEMENT_RETRY"));
        assertFalse(rendered.contains("T"));
        assertFalse(rendered.contains("Z"));
    }
}
