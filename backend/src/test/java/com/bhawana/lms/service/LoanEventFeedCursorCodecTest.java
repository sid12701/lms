package com.bhawana.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.service.LoanEventFeedCursorCodec.LoanEventFeedCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoanEventFeedCursorCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripsTheCompositeOrderingKey() {
        Instant occurredAt = Instant.parse("2026-08-17T06:13:52.694949Z");
        LoanEventFeedCursor cursor = new LoanEventFeedCursor("184467440737095516", 42L, occurredAt);

        String encoded = LoanEventFeedCursorCodec.encode(cursor, objectMapper);

        assertThat(encoded).doesNotContain("=", "+", "/");
        assertThat(LoanEventFeedCursorCodec.decode(encoded, objectMapper)).isEqualTo(cursor);
    }

    @Test
    void roundTripPreservesOccurredAtToSubSecondPrecision() {
        Instant occurredAt = Instant.parse("2026-08-17T06:13:52.123456789Z");
        LoanEventFeedCursor cursor = new LoanEventFeedCursor("1", 1L, occurredAt);

        String encoded = LoanEventFeedCursorCodec.encode(cursor, objectMapper);

        assertThat(LoanEventFeedCursorCodec.decode(encoded, objectMapper).occurredAt())
                .isEqualTo(occurredAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void noCursorStartsAtTheBeginningOfTheRetainedWindow(String blank) {
        assertThat(LoanEventFeedCursorCodec.decode(blank, objectMapper))
                .isEqualTo(LoanEventFeedCursorCodec.START);
        assertThat(LoanEventFeedCursorCodec.decode(null, objectMapper))
                .isEqualTo(LoanEventFeedCursorCodec.START);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-base64-at-all!!",
            "{\"transactionId\":\"5\",\"position\":1,\"occurredAt\":\"2026-08-17T06:13:52.694949Z\"}",
            // {"position":1,"occurredAt":"2026-08-17T06:13:52.694949Z"} — no transactionId
            "eyJwb3NpdGlvbiI6MSwib2NjdXJyZWRBdCI6IjIwMjYtMDgtMTdUMDY6MTM6NTIuNjk0OTQ5WiJ9",
            // {"transactionId":"five","position":1,"occurredAt":"2026-08-17T06:13:52.694949Z"}
            "eyJ0cmFuc2FjdGlvbklkIjoiZml2ZSIsInBvc2l0aW9uIjoxLCJvY2N1cnJlZEF0IjoiMjAyNi0wOC0xN1QwNjoxMzo1Mi42OTQ5NDlaIn0",
            // {"transactionId":"5","position":-1,"occurredAt":"2026-08-17T06:13:52.694949Z"}
            "eyJ0cmFuc2FjdGlvbklkIjoiNSIsInBvc2l0aW9uIjotMSwib2NjdXJyZWRBdCI6IjIwMjYtMDgtMTdUMDY6MTM6NTIuNjk0OTQ5WiJ9",
            // {"transactionId":"-5","position":1,"occurredAt":"2026-08-17T06:13:52.694949Z"}
            "eyJ0cmFuc2FjdGlvbklkIjoiLTUiLCJwb3NpdGlvbiI6MSwib2NjdXJyZWRBdCI6IjIwMjYtMDgtMTdUMDY6MTM6NTIuNjk0OTQ5WiJ9",
            // {"transactionId":"5","position":1} — no occurredAt
            "eyJ0cmFuc2FjdGlvbklkIjoiNSIsInBvc2l0aW9uIjoxfQ",
            // {"transactionId":"5","position":1,"occurredAt":"not-a-timestamp"}
            "eyJ0cmFuc2FjdGlvbklkIjoiNSIsInBvc2l0aW9uIjoxLCJvY2N1cnJlZEF0Ijoibm90LWEtdGltZXN0YW1wIn0"
    })
    void rejectsCursorsThePlatformNeverIssued(String cursor) {
        assertThatThrownBy(() -> LoanEventFeedCursorCodec.decode(cursor, objectMapper))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(exception -> assertThat(
                        ((BusinessRuleViolationException) exception).getErrorCode()
                ).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void rejectsATransactionIdOutsideTheUnsignedRangeRatherThanLettingSqlFail() {
        String beyondXid8 = encodeRaw(
                "{\"transactionId\":\"18446744073709551616\",\"position\":1,"
                        + "\"occurredAt\":\"2026-08-17T06:13:52.694949Z\"}"
        );

        assertThatThrownBy(() -> LoanEventFeedCursorCodec.decode(beyondXid8, objectMapper))
                .isInstanceOf(BusinessRuleViolationException.class);
        Instant occurredAt = Instant.parse("2026-08-17T06:13:52.694949Z");
        assertThat(LoanEventFeedCursorCodec.decode(
                encodeRaw(
                        "{\"transactionId\":\"18446744073709551615\",\"position\":1,"
                                + "\"occurredAt\":\"2026-08-17T06:13:52.694949Z\"}"
                ),
                objectMapper
        )).isEqualTo(new LoanEventFeedCursor("18446744073709551615", 1L, occurredAt));
    }

    private static String encodeRaw(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
