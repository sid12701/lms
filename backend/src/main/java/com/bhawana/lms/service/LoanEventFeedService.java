package com.bhawana.lms.service;

import com.bhawana.lms.common.api.error.BusinessRuleViolationException;
import com.bhawana.lms.common.api.error.CursorExpiredException;
import com.bhawana.lms.domain.LoanEvent;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.repo.LoanEventPartitionRepository;
import com.bhawana.lms.repo.LoanEventRepository;
import com.bhawana.lms.service.LoanEventFeedCursorCodec.LoanEventFeedCursor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Serves one LSP its own slice of the loan event log (ADR 0007).
 *
 * <p>The platform owns availability of events; the partner owns consumption. Delivery is
 * at-least-once — a partner that loses its place re-reads from an older cursor and sees events
 * again — so every event carries an identifier partners dedupe on.
 */
@Service
public class LoanEventFeedService {

    /** What a partner gets when it expresses no preference. */
    public static final int DEFAULT_LIMIT = 100;

    /** The hard ceiling, so no partner can ask for a response large enough to hurt either side. */
    public static final int MAX_LIMIT = 500;

    /**
     * The event types a partner may filter on — every type the feed serves.
     */
    private static final Set<LoanEventType> FILTERABLE_EVENT_TYPES = EnumSet.allOf(LoanEventType.class);

    /** Named on every {@link CursorExpiredException}, so an operator mid-incident knows what to call. */
    private static final String RESYNC_PATH = "GET /api/v1/lsp/loan-applications";

    private final LoanEventRepository loanEventRepository;
    private final LoanEventPartitionRepository loanEventPartitionRepository;
    private final ObjectMapper objectMapper;

    public LoanEventFeedService(
            LoanEventRepository loanEventRepository,
            LoanEventPartitionRepository loanEventPartitionRepository,
            ObjectMapper objectMapper
    ) {
        this.loanEventRepository = loanEventRepository;
        this.loanEventPartitionRepository = loanEventPartitionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads forward from {@code requestedCursor}, or from the beginning of the retained window when
     * no cursor is supplied, narrowed to {@code requestedEventTypes} when the partner supplies any.
     *
     * <p>Validation runs request-syntax first: a malformed cursor or an unrecognised event type is
     * the caller's mistake and is rejected before anything about the log's state is considered. Only
     * once the request itself is well-formed does expiry — a fact about the log, not the request — get
     * checked.
     */
    public LoanEventFeedPage read(
            UUID lspId,
            String requestedCursor,
            Integer requestedLimit,
            List<String> requestedEventTypes
    ) {
        int limit = effectiveLimit(requestedLimit);
        LoanEventFeedCursor cursor = LoanEventFeedCursorCodec.decode(requestedCursor, objectMapper);
        Set<LoanEventType> eventTypes = parseEventTypes(requestedEventTypes);
        requireCursorNotExpired(cursor);

        // One row beyond the page is what tells the partner whether to poll again immediately or
        // wait for its normal interval. With a filter applied it counts the events the filter
        // admits, so a narrow consumer is not told to poll again for events it would discard.
        List<LoanEvent> rows = loanEventRepository.findCommittedAfter(
                lspId,
                cursor.transactionId(),
                cursor.position(),
                eventTypes,
                limit + 1
        );
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }

        return new LoanEventFeedPage(
                rows.stream().map(this::toFeedEvent).toList(),
                nextCursor(rows, requestedCursor),
                limit,
                hasMore
        );
    }

    /**
     * A drained feed echoes the cursor it was called with, so a partner can store {@code nextCursor}
     * unconditionally instead of special-casing the empty page and re-reading events it already has.
     */
    private String nextCursor(List<LoanEvent> rows, String requestedCursor) {
        if (rows.isEmpty()) {
            return requestedCursor == null || requestedCursor.isBlank() ? null : requestedCursor;
        }
        LoanEvent last = rows.getLast();
        return LoanEventFeedCursorCodec.encode(
                new LoanEventFeedCursor(last.transactionId(), last.position(), last.occurredAt()),
                objectMapper
        );
    }

    /**
     * Fails loud rather than silently truncating: without this check a partner whose cursor names an
     * event the platform no longer retains would get an ordinary {@code 200} — indistinguishable from
     * a quiet day — instead of the gap it actually is (ADR 0007 point 6).
     *
     * <p>Only a partner-supplied cursor is checked. {@link LoanEventFeedCursorCodec#START} carries no
     * {@code occurredAt} and means "the beginning of the retained window, wherever that now is", so it
     * can never be expired by construction.
     */
    private void requireCursorNotExpired(LoanEventFeedCursor cursor) {
        if (cursor.occurredAt() == null) {
            return;
        }
        Optional<Instant> oldestRetainedBound = loanEventPartitionRepository.oldestRetainedBound();
        // An empty catalog means loan_event currently has no partition to write into at all, so it
        // cannot possibly hold the event this cursor names. The honest answer to a partner holding any
        // cursor in that state is "resync", not a 200 it would read as a quiet day.
        //
        // This is deliberately conservative at the boundary too: a cursor sitting on the very last
        // event of a month that just got dropped expires here even though that partner has not
        // actually lost anything (it already consumed everything the dropped month held). The
        // alternative — trying to prove "nothing was lost" from the cursor alone — risks the opposite
        // failure, silent truncation, which is the one this ticket exists to prevent.
        if (oldestRetainedBound.isEmpty() || cursor.occurredAt().isBefore(oldestRetainedBound.get())) {
            throw cursorExpired(oldestRetainedBound.orElse(null));
        }
    }

    private static CursorExpiredException cursorExpired(Instant retainedFrom) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        fieldErrors.put("cursor", "points before the oldest event still retained");
        fieldErrors.put("resyncPath", RESYNC_PATH);
        if (retainedFrom != null) {
            fieldErrors.put("retainedFrom", retainedFrom.toString());
        }
        return new CursorExpiredException(
                "CURSOR_EXPIRED",
                "Cursor points before the oldest event still retained. Resync by listing your loan "
                        + "applications at " + RESYNC_PATH + ", then resume the feed with no cursor to "
                        + "read from the beginning of the retained window.",
                fieldErrors
        );
    }

    /**
     * Reads the partner's event-type filter, accepting both the repeated
     * ({@code eventTypes=A&eventTypes=B}) and comma-separated ({@code eventTypes=A,B}) forms. No
     * filter means every type.
     *
     * <p>The filter narrows the response only. It never touches the cursor, which stays a position
     * in the unfiltered stream — so an LSP that widens the filter and rewinds receives everything it
     * skipped, which the write-time subscription filter this replaces could not offer (ADR 0007).
     *
     * <p>Anything the feed cannot serve is rejected rather than quietly dropped, in either
     * direction: a mistyped type would otherwise return an empty feed to a partner that believes it
     * asked for something, and a supplied-but-empty filter would otherwise widen silently to the
     * whole stream. Both are indistinguishable from a quiet day until the partner notices.
     */
    private static Set<LoanEventType> parseEventTypes(List<String> requestedEventTypes) {
        if (requestedEventTypes == null) {
            return Set.of();
        }
        Set<LoanEventType> eventTypes = EnumSet.noneOf(LoanEventType.class);
        for (String requestedValue : requestedEventTypes) {
            if (requestedValue == null) {
                continue;
            }
            for (String requestedName : requestedValue.split(",")) {
                String name = requestedName.trim();
                if (!name.isEmpty()) {
                    eventTypes.add(toEventType(name));
                }
            }
        }
        if (eventTypes.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "INVALID_EVENT_TYPE",
                    "eventTypes was supplied but names no event type. Omit it to receive every type.",
                    Map.of("eventTypes", "names no event type")
            );
        }
        return eventTypes;
    }

    private static LoanEventType toEventType(String name) {
        LoanEventType eventType;
        try {
            eventType = LoanEventType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw unknownEventType(name);
        }
        if (!FILTERABLE_EVENT_TYPES.contains(eventType)) {
            throw unknownEventType(name);
        }
        return eventType;
    }

    private static BusinessRuleViolationException unknownEventType(String name) {
        String known = FILTERABLE_EVENT_TYPES.stream()
                .map(LoanEventType::name)
                .collect(Collectors.joining(", "));
        return new BusinessRuleViolationException(
                "INVALID_EVENT_TYPE",
                "Unknown event type '" + name + "'. Known event types: " + known + ".",
                Map.of("eventTypes", "contains an unknown event type: " + name)
        );
    }

    private static int effectiveLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private LoanEventFeedEvent toFeedEvent(LoanEvent event) {
        JsonNode envelope = readEnvelope(event);
        return new LoanEventFeedEvent(
                event.id(),
                envelope.path("schemaVersion").asInt(),
                event.eventType().name(),
                event.occurredAt(),
                event.aggregateType(),
                event.aggregateId(),
                event.lspId(),
                envelope.path("lspCode").asText(null),
                event.loanApplicationId(),
                event.correlationId(),
                envelope.path("payload")
        );
    }

    private JsonNode readEnvelope(LoanEvent event) {
        try {
            return objectMapper.readTree(event.payloadJson());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read loan event payload for event " + event.id(), exception);
        }
    }

    /**
     * The cursor-paged shape the audit explorer already uses, extended with {@code hasMore} so a
     * partner knows whether more events are waiting.
     */
    public record LoanEventFeedPage(
            List<LoanEventFeedEvent> items,
            String nextCursor,
            int limit,
            boolean hasMore
    ) {
    }

    /**
     * One lifecycle fact as a partner sees it: the payload recorded on the loan event log, plus the
     * log's own identifiers.
     */
    public record LoanEventFeedEvent(
            UUID eventId,
            int schemaVersion,
            String eventType,
            Instant occurredAt,
            String aggregateType,
            String aggregateId,
            UUID lspId,
            String lspCode,
            UUID loanApplicationId,
            String correlationId,
            JsonNode payload
    ) {
    }
}
