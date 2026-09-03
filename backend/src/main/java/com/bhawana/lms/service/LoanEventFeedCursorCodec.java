package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Encodes an LSP's place in the loan event log as an opaque token.
 *
 * <p>The token carries the composite ordering key {@code (transactionId, position)} plus the
 * {@code occurredAt} of the event it names. {@code occurredAt} exists solely so the feed can check
 * cursor expiry against the oldest retained partition bound (ADR 0007 point 6) — partitioning is by
 * {@code occurred_at}, and nothing else in the cursor can be compared against a partition bound. It
 * is deliberately opaque beyond that: ADR 0007 rejected dense, gap-free sequence numbers precisely so
 * partners cannot build logic on the internal structure and break when the platform changes it. Treat
 * the encoded form as a bookmark to hand back, never as something to parse or compare.
 *
 * <p>Adding {@code occurredAt} is a breaking format change to a token this API has not yet shipped to
 * partners, which is why it is safe to make here rather than behind a version field.
 */
public final class LoanEventFeedCursorCodec {

    /**
     * The start of the retained window. Transaction ids are {@code xid8} values, which start above
     * this, so it sorts before every event the log can hold. {@code occurredAt} is {@code null},
     * which by construction happens only here: {@link #decode} rejects any partner-supplied token
     * that lacks a parseable {@code occurredAt} as {@code INVALID_CURSOR}, so a {@code null}
     * {@code occurredAt} is proof a cursor is this sentinel and never a real event's position.
     */
    public static final LoanEventFeedCursor START = new LoanEventFeedCursor("0", 0L, null);

    /** {@code xid8} is an unsigned 64-bit value. */
    private static final BigInteger MAX_TRANSACTION_ID = BigInteger.TWO.pow(64).subtract(BigInteger.ONE);

    private LoanEventFeedCursorCodec() {
    }

    public static String encode(LoanEventFeedCursor cursor, ObjectMapper objectMapper) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode loan event feed cursor.", exception);
        }
    }

    /** Decodes a partner-supplied cursor, or {@link #START} when none was supplied. */
    public static LoanEventFeedCursor decode(String encodedCursor, ObjectMapper objectMapper) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return START;
        }
        LoanEventFeedCursor cursor;
        try {
            byte[] json = Base64.getUrlDecoder().decode(encodedCursor);
            cursor = objectMapper.readValue(new String(json, StandardCharsets.UTF_8), LoanEventFeedCursor.class);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw invalidCursor();
        }
        if (cursor == null || cursor.transactionId() == null || cursor.position() < 0 || cursor.occurredAt() == null) {
            throw invalidCursor();
        }
        // The transaction id reaches SQL as a cast to xid8; anything outside the unsigned 64-bit
        // range would surface as a database error rather than a clean rejection.
        BigInteger transactionId;
        try {
            transactionId = new BigInteger(cursor.transactionId());
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
        if (transactionId.signum() < 0 || transactionId.compareTo(MAX_TRANSACTION_ID) > 0) {
            throw invalidCursor();
        }
        return cursor;
    }

    private static BusinessRuleViolationException invalidCursor() {
        return new BusinessRuleViolationException(
                "INVALID_CURSOR",
                "cursor is not valid",
                Map.of("cursor", "is not valid")
        );
    }

    /**
     * {@code occurredAt} is {@code null} only for {@link #START}; every cursor {@link #decode}
     * returns for a partner-supplied token carries a real event's {@code occurred_at}.
     */
    public record LoanEventFeedCursor(String transactionId, long position, Instant occurredAt) {
    }
}
