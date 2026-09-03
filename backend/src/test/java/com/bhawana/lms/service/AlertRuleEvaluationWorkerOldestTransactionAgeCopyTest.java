package com.bhawana.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link AlertRuleEvaluationWorker#describeTransactionAge(long)} renders the age carried in the
 * oldest-open-transaction alert's copy. The base unit is minutes rather than hours (unlike
 * {@link AlertRuleEvaluationWorker#describeStuckDuration}, which degrades from hours) because the
 * operational tolerance for a stalled partner feed is minutes, not hours, and the threshold itself
 * is configured in seconds for the same reason.
 */
class AlertRuleEvaluationWorkerOldestTransactionAgeCopyTest {

    @Test
    void singleMinuteIsNotPluralised() {
        assertEquals("1 minute", AlertRuleEvaluationWorker.describeTransactionAge(60));
    }

    @Test
    void multipleMinutesArePluralised() {
        assertEquals("12 minutes", AlertRuleEvaluationWorker.describeTransactionAge(12 * 60));
    }

    @Test
    void underAMinuteDegradesToSecondsRatherThanZeroMinutes() {
        assertEquals("1 second", AlertRuleEvaluationWorker.describeTransactionAge(1));
        assertEquals("45 seconds", AlertRuleEvaluationWorker.describeTransactionAge(45));
    }

    @Test
    void zeroSecondsDoesNotRenderAsZero() {
        assertEquals("1 second", AlertRuleEvaluationWorker.describeTransactionAge(0));
    }

    @Test
    void wholeMinuteBoundaryRoundsDownRatherThanUp() {
        // 90 seconds is 1 minute and 30 seconds; the copy is not expected to carry sub-minute
        // precision once it has crossed into minutes.
        assertEquals("1 minute", AlertRuleEvaluationWorker.describeTransactionAge(90));
    }
}
