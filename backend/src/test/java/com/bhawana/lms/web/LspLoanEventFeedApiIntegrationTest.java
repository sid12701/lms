package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.service.AlertRuleEvaluationWorker;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Seam A for the LSP loan event feed (spec 004): drive a real lifecycle change through the
 * existing command path, then assert what the partner actually sees on
 * {@code GET /api/v1/lsp/loan-events}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LspLoanEventFeedApiIntegrationTest {

    private static final String FEED_PATH = "/api/v1/lsp/loan-events";
    private static final java.util.concurrent.atomic.AtomicInteger IDENTITY_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Outside both V116's own range (-1..3) and {@code LoanEventPartitionLifecyclePostgresTest}'s
     * working offsets (-6, 4..7), so creating and dropping it here can never disturb a partition
     * another test in the suite depends on.
     */
    private static final int DROPPED_PARTITION_MONTH_OFFSET = -12;

    /**
     * Old enough to be more than 30 days back on every calendar date (worst case 59 days, 1 March
     * after a 28-day February), while still inside the retained window. Month -1 would be only 28
     * days back on 1 March, making the "really is more than 30 days old" guard calendar-dependent.
     */
    private static final int STALE_BUT_RETAINED_MONTH_OFFSET = -2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertRuleEvaluationWorker alertRuleEvaluationWorker;

    /** Set by a test that creates a partition of its own, so a failure mid-test cannot leave it behind. */
    private String partitionPendingCleanup;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
    }

    @AfterEach
    void dropAnyPartitionThisTestCreated() {
        if (partitionPendingCleanup != null) {
            String partition = partitionPendingCleanup;
            partitionPendingCleanup = null;
            TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.execute("DROP TABLE IF EXISTS " + partition));
        }
    }

    @Test
    void feedServesOwnLifecycleEventsWithCursorAdvanceAndHasMore() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-BASIC");
        String applicationId = createApplication(partner, "FEED-BASIC-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        JsonNode firstPoll = readFeed(partner.accessToken(), null, null);
        List<JsonNode> events = itemsOf(firstPoll);
        assertFalse(events.isEmpty(), "a created and documented application must produce feed events");
        assertFalse(firstPoll.get("hasMore").asBoolean(), "the whole feed fits in one default-sized page");
        assertTrue(firstPoll.get("nextCursor").isTextual(), "a non-empty page always names the resume point");

        JsonNode first = events.getFirst();
        assertEquals("LOAN_CREATED", first.get("eventType").asText());
        assertEquals(applicationId, first.get("loanApplicationId").asText());
        assertEquals(partner.lspId(), first.get("lspId").asText());
        assertEquals(1, first.get("schemaVersion").asInt());
        assertTrue(first.get("eventId").isTextual(), "partners dedupe on a stable event identifier");
        assertTrue(first.get("occurredAt").isTextual(), "every event carries its business-event time");
        assertTrue(first.get("payload").isObject());

        assertTrue(
                eventTypesOf(events).containsAll(List.of("LOAN_CREATED", "DOCUMENTS_UPLOADED")),
                "the feed carries every lifecycle fact the command path produced: " + eventTypesOf(events)
        );

        List<String> pagedOneByOne = new ArrayList<>();
        String cursor = null;
        boolean hasMore = true;
        // Bounded so a cursor that stopped advancing fails here rather than spinning forever.
        int polls = 0;
        while (hasMore) {
            assertTrue(++polls <= events.size() + 1, "the cursor must advance on every poll");
            JsonNode page = readFeed(partner.accessToken(), cursor, 1);
            List<JsonNode> pageItems = itemsOf(page);
            assertTrue(pageItems.size() <= 1, "the requested limit is honoured");
            pageItems.forEach(item -> pagedOneByOne.add(item.get("eventId").asText()));
            cursor = page.get("nextCursor").asText(null);
            hasMore = page.get("hasMore").asBoolean();
        }
        assertEquals(
                events.stream().map(event -> event.get("eventId").asText()).toList(),
                pagedOneByOne,
                "paging one event at a time yields the same feed order with no gaps and no duplicates"
        );

        JsonNode drained = readFeed(partner.accessToken(), cursor, null);
        assertTrue(itemsOf(drained).isEmpty(), "a fully drained cursor returns nothing new");
        assertFalse(drained.get("hasMore").asBoolean());
        assertEquals(cursor, drained.get("nextCursor").asText(null), "an empty page echoes the cursor back");
    }

    /**
     * Feed order is the {@code xid8} transaction id, and the cursor compares that same number — so
     * neither may ever be read as text. Ids that differ in width are where the two would part
     * company: sorted as numbers 98, 99, 100, 101 read in that order, sorted as text they read 100,
     * 101, 98, 99. A consumer paging the text order would take a cursor at 101 and leave 98 and 99
     * numerically beneath it, never to be served again — silent, permanent loss, not a reordering.
     *
     * <p>The ids are pinned rather than provoked. Which ids a lifecycle change happens to land on is
     * a function of how many transactions the database ran before it, so a test that hoped to
     * straddle a boundary would pass or fail on unrelated test ordering. These four sit far below
     * anything this database has reached since Flyway ran, so they are always below the snapshot
     * {@code xmin} and always visible.
     */
    @Test
    void pagingNeverSkipsEventsWhoseTransactionIdsDifferInWidth() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-TXID-WIDTH");
        String applicationId = createApplication(partner, "FEED-TXID-WIDTH-001");

        List<String> straddlingTheBoundaryInNumericOrder = new ArrayList<>();
        for (long transactionId : List.of(98L, 99L, 100L, 101L)) {
            straddlingTheBoundaryInNumericOrder.add(
                    insertLoanEventWithTransactionId(
                            UUID.fromString(partner.lspId()),
                            UUID.fromString(applicationId),
                            transactionId
                    ).toString()
            );
        }

        List<String> singlePage = eventIdsOf(itemsOf(readFeed(partner.accessToken(), null, 500)));
        assertEquals(
                straddlingTheBoundaryInNumericOrder,
                singlePage.subList(0, straddlingTheBoundaryInNumericOrder.size()),
                "feed order is the transaction id as a number, so a narrower id never sorts after a wider one"
        );

        List<String> pagedOneByOne = new ArrayList<>();
        String cursor = null;
        boolean hasMore = true;
        int polls = 0;
        while (hasMore) {
            assertTrue(++polls <= singlePage.size() + 1, "the cursor must advance on every poll");
            JsonNode page = readFeed(partner.accessToken(), cursor, 1);
            itemsOf(page).forEach(item -> pagedOneByOne.add(item.get("eventId").asText()));
            cursor = page.get("nextCursor").asText(null);
            hasMore = page.get("hasMore").asBoolean();
        }
        assertEquals(
                singlePage,
                pagedOneByOne,
                "paging one at a time across a digit-width boundary loses nothing: a cursor taken from "
                        + "one page must never leave an earlier event beneath it"
        );
    }

    @Test
    void feedWithoutCursorRestartsFromTheBeginningOfTheRetainedWindow() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-REWIND");
        String applicationId = createApplication(partner, "FEED-REWIND-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        List<JsonNode> firstRead = itemsOf(readFeed(partner.accessToken(), null, null));
        List<JsonNode> secondRead = itemsOf(readFeed(partner.accessToken(), null, null));

        assertEquals(
                firstRead.stream().map(event -> event.get("eventId").asText()).toList(),
                secondRead.stream().map(event -> event.get("eventId").asText()).toList(),
                "delivery is at-least-once: replaying without a cursor re-serves the same identifiers"
        );
    }

    @Test
    void oneLspNeverObservesAnotherLspEvents() throws Exception {
        PartnerFixture apex = onboardPartner("FEED-APEX");
        PartnerFixture north = onboardPartner("FEED-NORTH");
        String apexApplication = createApplication(apex, "APEX-ISOLATION-001");
        String northApplication = createApplication(north, "NORTH-ISOLATION-001");
        uploadAllRequiredDocuments(apex.accessToken(), apexApplication);
        uploadAllRequiredDocuments(north.accessToken(), northApplication);

        List<JsonNode> apexFeed = itemsOf(readFeed(apex.accessToken(), null, 500));
        List<JsonNode> northFeed = itemsOf(readFeed(north.accessToken(), null, 500));

        assertFalse(apexFeed.isEmpty());
        assertFalse(northFeed.isEmpty());
        assertTrue(
                apexFeed.stream().allMatch(event -> apex.lspId().equals(event.get("lspId").asText())),
                "Apex sees only its own events"
        );
        assertTrue(
                northFeed.stream().allMatch(event -> north.lspId().equals(event.get("lspId").asText())),
                "North sees only its own events"
        );

        List<String> apexApplications = apexFeed.stream()
                .map(event -> event.get("loanApplicationId").asText())
                .distinct()
                .toList();
        List<String> northApplications = northFeed.stream()
                .map(event -> event.get("loanApplicationId").asText())
                .distinct()
                .toList();
        assertEquals(List.of(apexApplication), apexApplications);
        assertEquals(List.of(northApplication), northApplications);

        assertTrue(
                apexFeed.stream()
                        .map(event -> event.get("eventId").asText())
                        .noneMatch(northFeed.stream()
                                .map(event -> event.get("eventId").asText())
                                .toList()::contains),
                "neither LSP can observe the other's events"
        );
    }

    @Test
    void anotherLspCursorNeverLeaksEventsAcrossTenants() throws Exception {
        PartnerFixture apex = onboardPartner("FEED-XCURSOR-A");
        PartnerFixture north = onboardPartner("FEED-XCURSOR-B");
        uploadAllRequiredDocuments(apex.accessToken(), createApplication(apex, "APEX-XCURSOR-001"));
        String northApplication = createApplication(north, "NORTH-XCURSOR-001");
        uploadAllRequiredDocuments(north.accessToken(), northApplication);

        String apexStartCursor = readFeed(apex.accessToken(), null, 1).get("nextCursor").asText();
        List<JsonNode> northFromApexCursor = itemsOf(readFeed(north.accessToken(), apexStartCursor, 500));

        assertTrue(
                northFromApexCursor.stream().allMatch(event -> north.lspId().equals(event.get("lspId").asText())),
                "another LSP's cursor still only ever resolves against the caller's own feed"
        );
        // Named explicitly, because "sees nothing at all" would satisfy the leak assertion above while
        // being its own failure: a foreign cursor must not silently swallow the caller's own events.
        assertEquals(
                eventIdsOf(itemsOf(readFeed(north.accessToken(), null, 500))),
                eventIdsOf(northFromApexCursor),
                "North's own events all sit after Apex's first, so North still receives every one of them"
        );
    }

    @Test
    void limitHasASensibleDefaultAndAHardMaximum() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-LIMIT");
        createApplication(partner, "FEED-LIMIT-001");

        assertEquals(100, readFeed(partner.accessToken(), null, null).get("limit").asInt());
        assertEquals(500, readFeed(partner.accessToken(), null, 99_999).get("limit").asInt());
        assertEquals(100, readFeed(partner.accessToken(), null, 0).get("limit").asInt());
        assertEquals(25, readFeed(partner.accessToken(), null, 25).get("limit").asInt());
    }

    @Test
    void malformedCursorIsRejectedRatherThanSilentlyIgnored() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-BADCURSOR");

        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("cursor", "not-a-real-cursor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    /**
     * Criterion 5: a partner must be able to tell a bug in its own integration from having merely
     * fallen behind. The two failures share nothing but both being 4xx — different status, different
     * error code — asserted side by side so a regression collapsing them together is caught here.
     */
    @Test
    void malformedAndExpiredCursorsAreDistinguishableByStatusAndCode() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-CURSORKINDS");
        String applicationId = createApplication(partner, "FEED-CURSORKINDS-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("cursor", "not-a-real-cursor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));

        String cursor = cursorNamingAnEventInADroppablePartition(partner, applicationId);
        dropPartition(partitionPendingCleanup);

        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("cursor", cursor))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("CURSOR_EXPIRED"));
    }

    @Test
    void aCursorIntoADroppedPartitionFailsLoudWithTheResyncPath() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-EXPIRY");
        String applicationId = createApplication(partner, "FEED-EXPIRY-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        String cursor = cursorNamingAnEventInADroppablePartition(partner, applicationId);

        // Proves the 410 below comes from the partition being gone, not merely from the event's age:
        // while the partition still exists, the very same cursor reads normally.
        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("cursor", cursor))
                .andExpect(status().isOk());

        dropPartition(partitionPendingCleanup);

        MvcResult expired = mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("cursor", cursor))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("CURSOR_EXPIRED"))
                .andExpect(jsonPath("$.message").value(containsString("GET /api/v1/lsp/loan-applications")))
                .andReturn();

        List<JsonNode> violations = new ArrayList<>();
        objectMapper.readTree(expired.getResponse().getContentAsString()).get("violations").forEach(violations::add);
        assertTrue(
                violations.stream().anyMatch(violation ->
                        "resyncPath".equals(violation.get("field").asText())
                                && "GET /api/v1/lsp/loan-applications".equals(violation.get("message").asText())
                ),
                "the machine-readable resync path must be present so an operator's tooling, not just a "
                        + "human reading the message, can find it: " + violations
        );
    }

    @Test
    void aCursorOlderThanThirtyDaysButStillInsideTheRetainedWindowKeepsWorking() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-STALE");
        String applicationId = createApplication(partner, "FEED-STALE-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        // Month -2, day 1 is at least 59 days before "now" in every calendar — exactly the case a flat
        // "now() - 30 days" check would wrongly expire, since the partition holding it is not due to be
        // dropped for weeks yet. Month -1 is NOT safe here: on 1-3 March it is only 28-30 days back, so
        // the fixture guard below would fail for reasons unrelated to any code change. V116 does not
        // partition -2, so this test creates it and @AfterEach drops it.
        partitionPendingCleanup = createPartitionForMonthOffset(STALE_BUT_RETAINED_MONTH_OFFSET);
        insertBackdatedLoanEvent(
                UUID.fromString(partner.lspId()),
                UUID.fromString(applicationId),
                firstInstantOfMonthOffset(STALE_BUT_RETAINED_MONTH_OFFSET)
        );

        JsonNode page = readFeed(partner.accessToken(), null, 500);
        JsonNode staleEvent = itemsOf(page).getLast();
        Instant staleOccurredAt = Instant.parse(staleEvent.get("occurredAt").asText());
        assertTrue(
                staleOccurredAt.isBefore(Instant.now().minus(Duration.ofDays(30))),
                "fixture is only meaningful if the event really is more than 30 days old: " + staleOccurredAt
        );
        String staleCursor = page.get("nextCursor").asText();

        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);

        List<String> eventTypesAfterStaleCursor = eventTypesOf(itemsOf(readFeed(partner.accessToken(), staleCursor, 500)));
        assertTrue(
                eventTypesAfterStaleCursor.contains("DISBURSEMENT_COMPLETED"),
                "a cursor that is merely old but still inside the retained window keeps working: "
                        + eventTypesAfterStaleCursor
        );
    }

    @Test
    void eventTypeFilterNarrowsTheResponseOnly() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-FILTER");
        String applicationId = createApplication(partner, "FEED-FILTER-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);

        // Omitting the filter returns all of the LSP's event types.
        List<JsonNode> unfiltered = itemsOf(readFeed(partner.accessToken(), null, 500));
        assertTrue(
                eventTypesOf(unfiltered).containsAll(List.of(
                        "LOAN_CREATED",
                        "DOCUMENTS_UPLOADED",
                        "DISBURSEMENT_REQUESTED",
                        "DISBURSEMENT_COMPLETED"
                )),
                "the unfiltered feed is the baseline every filtered read is a subset of: "
                        + eventTypesOf(unfiltered)
        );

        // A narrowed read is exactly the unfiltered read with the other types dropped — same events,
        // same feed order. That is the whole of what the filter does.
        for (List<String> filter : List.of(
                List.of("DOCUMENTS_UPLOADED"),
                List.of("DISBURSEMENT_REQUESTED", "DISBURSEMENT_COMPLETED"),
                List.of("LOAN_CREATED", "DOCUMENTS_UPLOADED", "DISBURSEMENT_COMPLETED")
        )) {
            assertEquals(
                    eventIdsOf(unfiltered.stream()
                            .filter(event -> filter.contains(event.get("eventType").asText()))
                            .toList()),
                    eventIdsOf(itemsOf(readFeed(partner.accessToken(), null, 500, filter))),
                    "filtering on " + filter + " narrows the response and changes nothing else"
            );
        }

        // Both spellings of the filter mean the same thing: repeated parameters, and one
        // comma-separated value.
        MvcResult commaSeparated = mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("limit", "500")
                        .param("eventTypes", "DISBURSEMENT_REQUESTED,DISBURSEMENT_COMPLETED"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(
                eventIdsOf(itemsOf(readFeed(partner.accessToken(), null, 500, List.of(
                        "DISBURSEMENT_REQUESTED",
                        "DISBURSEMENT_COMPLETED"
                )))),
                eventIdsOf(itemsOf(objectMapper.readTree(commaSeparated.getResponse().getContentAsString())))
        );

        // hasMore counts what the filter admits, not what is behind it: one LOAN_CREATED exists, so a
        // page of one drains the filtered feed even though later events are still waiting.
        JsonNode createdOnly = readFeed(partner.accessToken(), null, 1, List.of("LOAN_CREATED"));
        assertEquals(1, itemsOf(createdOnly).size());
        assertFalse(createdOnly.get("hasMore").asBoolean(), "hasMore reports the filtered feed, not the stream");

        // A type this LSP has no events for is an empty page, not an error.
        JsonNode neverHappened = readFeed(partner.accessToken(), null, 500, List.of("LOAN_FULLY_REPAID"));
        assertTrue(itemsOf(neverHappened).isEmpty());
        assertFalse(neverHappened.get("hasMore").asBoolean());
    }

    @Test
    void wideningTheFilterAndRewindingDeliversTheEventsItPreviouslySkipped() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-REWIDEN");
        String applicationId = createApplication(partner, "FEED-REWIDEN-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);

        List<String> everyEventId = eventIdsOf(itemsOf(readFeed(partner.accessToken(), null, 500)));

        // The integration launches caring only about document completeness, so that is all it asks for.
        JsonNode narrowRead = readFeed(partner.accessToken(), null, 500, List.of("DOCUMENTS_UPLOADED"));
        List<String> narrowlyConsumed = eventIdsOf(itemsOf(narrowRead));
        String narrowCursor = narrowRead.get("nextCursor").asText();
        assertFalse(narrowlyConsumed.isEmpty());
        assertFalse(narrowRead.get("hasMore").asBoolean(), "the narrow consumer drained its filtered feed");

        List<String> skipped = everyEventId.stream().filter(id -> !narrowlyConsumed.contains(id)).toList();
        assertFalse(skipped.isEmpty(), "the narrow filter must actually have skipped something");

        // The filter never changed what the cursor means: it is a position in the unfiltered stream, so
        // widening alone does not resurrect events the narrow read already paged past.
        List<String> widerTypes = List.of(
                "LOAN_CREATED",
                "LOAN_STATUS_CHANGED",
                "DOCUMENTS_UPLOADED",
                "DISBURSEMENT_REQUESTED",
                "DISBURSEMENT_COMPLETED"
        );
        List<String> forwardOnly = eventIdsOf(itemsOf(
                readFeed(partner.accessToken(), narrowCursor, 500, widerTypes)
        ));
        assertFalse(
                forwardOnly.contains(everyEventId.getFirst()),
                "an event behind the cursor stays behind it until the consumer rewinds"
        );

        // Rewinding with the wider filter hands back everything the narrow filter discarded. This is what
        // a write-time subscription filter could never offer: filtering costs the partner no history.
        List<String> rewound = eventIdsOf(itemsOf(readFeed(partner.accessToken(), null, 500, widerTypes)));
        assertTrue(
                rewound.containsAll(skipped),
                "widening the filter and rewinding delivers every event the narrow read filtered out"
        );
        assertEquals(
                everyEventId,
                rewound,
                "a filter admitting every recorded type reads identically to no filter at all"
        );
    }

    @Test
    void unrecognisedEventTypeIsRejectedRatherThanSilentlyReturningNothing() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-BADFILTER");
        createApplication(partner, "FEED-BADFILTER-001");

        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("eventTypes", "LOAN_CREATED,LOAN_ABANDONED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_EVENT_TYPE"))
                .andExpect(jsonPath("$.message").value(containsString("LOAN_ABANDONED")))
                .andExpect(jsonPath("$.message").value(containsString("LOAN_CREATED")));

        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("eventTypes", "loan_created"))
                .andExpect(status().isOk());

        // The type no longer exists, so it is rejected the same way any unknown type is.
        mockMvc.perform(get(FEED_PATH)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .param("eventTypes", "LOAN_DISBURSEMENT_UPDATED"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_EVENT_TYPE"));

        // A filter that was sent but names nothing is a mistake, not a request for everything:
        // widening a partner's stream silently is the more dangerous reading.
        for (String emptyFilter : List.of("", " ", ",")) {
            mockMvc.perform(get(FEED_PATH)
                            .header("Authorization", "Bearer " + partner.accessToken())
                            .param("eventTypes", emptyFilter))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("INVALID_EVENT_TYPE"));
        }
    }

    @Test
    void disbursementAndRepaymentReachTheFeed() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-SERVICING");
        String applicationId = createApplication(partner, "FEED-SERVICING-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        String loanId = loanAccountId(partner.accessToken(), applicationId);
        recordPayment(partner.accessToken(), loanId);

        List<String> eventTypes = eventTypesOf(itemsOf(readFeed(partner.accessToken(), null, 500)));

        assertTrue(
                eventTypes.containsAll(List.of(
                        "LOAN_CREATED",
                        "LOAN_STATUS_CHANGED",
                        "DOCUMENTS_UPLOADED",
                        "DISBURSEMENT_REQUESTED",
                        "DISBURSEMENT_COMPLETED",
                        "LOAN_REPAYMENT_RECORDED"
                )),
                "the feed carries everything the webhook path carried: " + eventTypes
        );
    }

    /**
     * Issue 08: delinquency bucket transitions become recorded facts, in both directions — a loan
     * entering delinquency and a loan curing out of it — and a re-run with nothing changed appends no
     * further event (mirrors {@code AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest}'s
     * {@code shouldPersistDelinquencyState} guard, but observed at the HTTP seam instead of via the
     * ops-alert side channel).
     */
    @Test
    void delinquencyBucketChangeIsObservableInBothDirectionsWithNoDuplicateOnUnchangedRerun() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-DELINQUENCY");
        String applicationId = createApplication(partner, "FEED-DELINQUENCY-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);
        requestDisbursement(applicationId);
        resolveDisbursement(applicationId);
        String loanAccountId = loanAccountId(partner.accessToken(), applicationId);

        transitionApplicationToUnderRepayment(applicationId);
        setFirstInstallmentDueDate(loanAccountId, LocalDate.now().minusDays(10));
        evaluateScheduledRulesAsAdmin();

        List<JsonNode> afterWorsening = delinquencyEvents(partner.accessToken());
        assertEquals(
                1,
                afterWorsening.size(),
                "the loan crossed exactly one bucket boundary so far: " + eventTypesOf(afterWorsening)
        );
        JsonNode worsened = afterWorsening.get(0);
        assertEquals("LOAN_APPLICATION", worsened.get("aggregateType").asText());
        assertEquals(applicationId, worsened.get("loanApplicationId").asText());
        JsonNode worsenedPayload = worsened.get("payload");
        assertEquals("CURRENT", worsenedPayload.get("fromBucket").asText());
        assertEquals("DPD_1_30", worsenedPayload.get("toBucket").asText());
        assertEquals(10, worsenedPayload.get("maxDaysPastDue").asInt());
        assertTrue(
                worsenedPayload.get("overdueAmount") != null && !worsenedPayload.get("overdueAmount").isNull(),
                "an LSP must be able to act without recomputing the bucket from repayment history"
        );

        // Re-run with nothing changed: the state write is a no-op, and so must the event append be.
        evaluateScheduledRulesAsAdmin();
        assertEquals(
                1,
                delinquencyEvents(partner.accessToken()).size(),
                "an unchanged re-evaluation must not duplicate the transition event"
        );

        // The cure: the installment moves back into the future.
        setFirstInstallmentDueDate(loanAccountId, LocalDate.now().plusDays(5));
        evaluateScheduledRulesAsAdmin();

        List<JsonNode> afterCure = delinquencyEvents(partner.accessToken());
        assertEquals(2, afterCure.size(), "the cure is a second, distinct event: " + eventTypesOf(afterCure));
        JsonNode cured = afterCure.get(1);
        JsonNode curedPayload = cured.get("payload");
        assertEquals("DPD_1_30", curedPayload.get("fromBucket").asText());
        assertEquals(
                "CURRENT",
                curedPayload.get("toBucket").asText(),
                "toBucket: CURRENT is the cure signal a collections consumer stops on"
        );

        assertEquals(
                List.of(applicationId, applicationId),
                afterCure.stream().map(event -> event.get("loanApplicationId").asText()).toList(),
                "both transitions belong to the same loan and must appear in per-loan order"
        );
    }

    @Test
    void reconciliationParkedDisbursementProducesNoCompletionEvent() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-PARKED");
        String applicationId = createApplication(partner, "FEED-PARKED-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);
        requestDisbursement(applicationId);
        parkDisbursementForReconciliation(applicationId);

        List<String> eventTypes = eventTypesOf(itemsOf(readFeed(partner.accessToken(), null, 500)));

        assertTrue(eventTypes.contains("DISBURSEMENT_REQUESTED"));
        assertFalse(
                eventTypes.contains("DISBURSEMENT_COMPLETED"),
                "a disbursement parked for reconciliation has not completed: " + eventTypes
        );
    }

    /**
     * Issue 07: the disbursement path's intermediate states are each observable on the feed, not just
     * the terminal outcome. Drives a disbursement into a reconciliation park and asserts the sequence
     * lands on the partner's feed in per-loan order, as ADR 0007's Consequences section names it:
     * {@code DISBURSEMENT_REQUESTED -> DISBURSEMENT_PENDING_RECONCILIATION}.
     *
     * <p>It deliberately stops at the park. The command path does currently re-admit a parked account
     * for a fresh attempt, but CONTEXT.md § Disbursement is explicit that a parked attempt is in
     * flight and past the point of no return — "no second initiation" until funds are confirmed
     * returned, a confirmation this system has no transition for. Driving a second attempt here would
     * cement that deviation in a regression test; the terminal path is covered by
     * {@link #disbursementAndRepaymentReachTheFeed()} instead.
     */
    @Test
    void disbursementParkedForReconciliationProducesTheFullSequenceInPerLoanOrder() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-RECONCILE");
        String applicationId = createApplication(partner, "FEED-RECONCILE-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        requestDisbursement(applicationId);
        parkDisbursementForReconciliation(applicationId);

        List<String> disbursementEventTypes = eventTypesOf(itemsOf(readFeed(
                partner.accessToken(),
                null,
                500,
                List.of("DISBURSEMENT_REQUESTED", "DISBURSEMENT_PENDING_RECONCILIATION", "DISBURSEMENT_COMPLETED")
        )));

        assertEquals(
                List.of("DISBURSEMENT_REQUESTED", "DISBURSEMENT_PENDING_RECONCILIATION"),
                disbursementEventTypes,
                "a loan awaiting reconciliation must read as exactly that, in per-loan order, not be "
                        + "indistinguishable from a stall: " + disbursementEventTypes
        );

        JsonNode reconciliationEvent = itemsOf(readFeed(partner.accessToken(), null, 500)).stream()
                .filter(event -> "DISBURSEMENT_PENDING_RECONCILIATION".equals(event.get("eventType").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reconciliation park must reach the feed"));

        assertEquals("LOAN_ACCOUNT", reconciliationEvent.get("aggregateType").asText());
        assertEquals(applicationId, reconciliationEvent.get("loanApplicationId").asText());
        JsonNode payload = reconciliationEvent.get("payload");
        assertEquals(
                "DISBURSEMENT_PENDING_RECONCILIATION",
                payload.get("loanAccountStatus").asText(),
                "the event must carry enough for an LSP to update its own record without a second lookup"
        );
        assertTrue(payload.get("loanAccountId").isTextual());
        assertTrue(payload.get("accountNumber").isTextual());
        assertTrue(payload.get("principalAmount") != null && !payload.get("principalAmount").isNull());
        assertEquals(
                "DISBURSEMENT_RETRY",
                payload.get("applicationStatus").asText(),
                "a parked attempt leaves the application retryable, and the partner must be able to see "
                        + "that without a second lookup"
        );
    }

    /**
     * A technical decline emits {@code DISBURSEMENT_FAILED} but leaves the application retryable, so
     * the same event type covers "this loan will never fund" and "this attempt failed, another is
     * coming". The account status is {@code DISBURSEMENT_FAILED} either way, so the payload has to
     * carry what separates them — otherwise a partner writes off a loan that later funds.
     */
    @Test
    void aRetryableDisbursementFailureIsDistinguishableFromATerminalOne() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-DECLINE");
        String applicationId = createApplication(partner, "FEED-DECLINE-001");
        uploadAllRequiredDocuments(partner.accessToken(), applicationId);

        requestDisbursement(applicationId);
        failDisbursement(applicationId);

        JsonNode failureEvent = itemsOf(readFeed(partner.accessToken(), null, 500)).stream()
                .filter(event -> "DISBURSEMENT_FAILED".equals(event.get("eventType").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the failed attempt must reach the feed"));

        JsonNode payload = failureEvent.get("payload");
        assertEquals("DISBURSEMENT_FAILED", payload.get("loanAccountStatus").asText());
        assertEquals(
                "DISBURSEMENT_RETRY",
                payload.get("applicationStatus").asText(),
                "a technical decline is not the end of the loan, and the event must say so"
        );
        assertEquals(
                "TECHNICAL",
                payload.get("declineKind").asText(),
                "the decline kind is what tells a partner whether to expect another attempt"
        );
    }

    @Test
    void payloadsAreFullAndUnmasked() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-UNMASKED");
        String applicationId = createApplication(partner, "FEED-UNMASKED-001");
        String borrowerId = applicationDetail(partner.accessToken(), applicationId).get("borrowerId").asText();

        mockMvc.perform(patch("/api/v1/lsp/borrowers/{borrowerId}/bank-details", borrowerId)
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankAccountNumber", "987654321098",
                                "bankName", "Demo Bank",
                                "ifscCode", "HDFC0001234",
                                "accountHolderName", "Anika Sharma"
                        ))))
                .andExpect(status().isOk());

        JsonNode bankDetailsEvent = itemsOf(readFeed(partner.accessToken(), null, 500)).stream()
                .filter(event -> "BORROWER_BANK_DETAILS_UPDATED".equals(event.get("eventType").asText()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("bank details change must reach the feed"));

        assertEquals(
                "987654321098",
                bankDetailsEvent.get("payload").get("bankAccountNumber").asText(),
                "one schema for every LSP, carrying full unmasked data (ADR 0007 as clarified by spec 003)"
        );
    }

    @Test
    void feedIsClosedToCallersWithoutAnLspApiClientToken() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-AUTHZ");

        mockMvc.perform(get(FEED_PATH))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(FEED_PATH).with(systemAdmin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(FEED_PATH).header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void feedInheritsTheLspSurfaceIpAllowlist() throws Exception {
        PartnerFixture partner = onboardPartner("FEED-ALLOWLIST");
        createApplication(partner, "FEED-ALLOWLIST-001");
        addApiCidr(partner.lspId(), "10.0.0.0/24");

        mockMvc.perform(get(FEED_PATH)
                        .with(request -> {
                            request.setRemoteAddr("192.168.99.99");
                            return request;
                        })
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("IP_NOT_ALLOWED"));

        mockMvc.perform(get(FEED_PATH)
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.50");
                            return request;
                        })
                        .header("Authorization", "Bearer " + partner.accessToken()))
                .andExpect(status().isOk());
    }

    private void addApiCidr(String lspId, String cidr) throws Exception {
        mockMvc.perform(post("/api/v1/internal/admin/lsps/{lspId}/api-ip-allowlist", lspId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cidr", cidr,
                                "description", "feed allowlist test"
                        ))))
                .andExpect(status().isCreated());
    }

    /**
     * Puts one event in a partition of its own and returns the cursor naming it, leaving that
     * partition ready to drop — the state a consumer that fell behind retention ends up in. The drop
     * is left to the caller so a test can assert what the same cursor does on either side of it.
     *
     * <p>The backdated row is inserted last, so it sorts last in feed order
     * {@code (transaction_id, position)} whatever its {@code occurred_at}, and {@code nextCursor}
     * therefore names it.
     */
    private String cursorNamingAnEventInADroppablePartition(PartnerFixture partner, String applicationId)
            throws Exception {
        partitionPendingCleanup = createPartitionForMonthOffset(DROPPED_PARTITION_MONTH_OFFSET);
        insertBackdatedLoanEvent(
                UUID.fromString(partner.lspId()),
                UUID.fromString(applicationId),
                firstInstantOfMonthOffset(DROPPED_PARTITION_MONTH_OFFSET)
        );
        return readFeed(partner.accessToken(), null, 500).get("nextCursor").asText();
    }

    /**
     * Creates a partition of {@code loan_event} for the given month offset, with bounds the database
     * computes exactly as V116/V117 do — never derived in the JVM, which could disagree with the
     * database's own timezone at a month boundary. Runs under the admin scope because this
     * {@code JdbcTemplate} sits on the app's real tenant-routing datasource, which requires a resolved
     * scope for every acquisition, DDL included.
     */
    private String createPartitionForMonthOffset(int monthOffset) {
        String partitionName = TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(
                "SELECT 'loan_event_' || to_char("
                        + "date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => ?), 'YYYY_MM')",
                String.class,
                monthOffset
        ));
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.execute(
                """
                DO $do$
                DECLARE
                    partition_start TIMESTAMPTZ :=
                        date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => %d);
                BEGIN
                    EXECUTE format(
                        'CREATE TABLE %%I PARTITION OF loan_event FOR VALUES FROM (%%L) TO (%%L)',
                        'loan_event_' || to_char(partition_start, 'YYYY_MM'),
                        partition_start,
                        partition_start + INTERVAL '1 month'
                    );
                END
                $do$
                """.formatted(monthOffset)
        ));
        return partitionName;
    }

    private void dropPartition(String partitionName) {
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.execute("DROP TABLE " + partitionName));
    }

    /** The database's own {@code date_trunc('month', ...)} for {@code monthOffset}, not the JVM's. */
    private Instant firstInstantOfMonthOffset(int monthOffset) {
        return TenantScopedExecution.callAsAdmin(() -> jdbcTemplate.queryForObject(
                "SELECT date_trunc('month', CURRENT_TIMESTAMP) + make_interval(months => ?)",
                Timestamp.class,
                monthOffset
        )).toInstant();
    }

    /**
     * Appends a {@code loan_event} row directly, bypassing the command path, so a test can put an
     * event at an arbitrary {@code occurred_at} the ordinary lifecycle never produces. Wrapped in the
     * admin scope for the same reason as {@link #createPartitionForMonthOffset}: this datasource
     * routes on tenant context regardless of statement content.
     */
    private void insertBackdatedLoanEvent(UUID lspId, UUID applicationId, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(
                "INSERT INTO loan_event (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, payload_json, occurred_at) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, CAST(? AS jsonb), ?)",
                eventId, lspId, applicationId.toString(), applicationId, "{}", Timestamp.from(occurredAt)
        ));
    }

    /**
     * Appends a {@code loan_event} row with an explicitly chosen {@code transaction_id}, which the
     * ordinary path always defaults to {@code pg_current_xact_id()}. Lets a test place rows at
     * transaction ids it picked rather than at whatever the database happened to be up to. Admin
     * scope for the same reason as {@link #insertBackdatedLoanEvent}.
     */
    private UUID insertLoanEventWithTransactionId(UUID lspId, UUID applicationId, long transactionId) {
        UUID eventId = UUID.randomUUID();
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(
                "INSERT INTO loan_event (id, lsp_id, event_type, aggregate_type, aggregate_id, "
                        + "loan_application_id, payload_json, occurred_at, transaction_id) "
                        + "VALUES (?, ?, 'LOAN_CREATED', 'LOAN_APPLICATION', ?, ?, CAST(? AS jsonb), "
                        + "CURRENT_TIMESTAMP, CAST(? AS xid8))",
                eventId, lspId, applicationId.toString(), applicationId, "{}", Long.toString(transactionId)
        ));
        return eventId;
    }

    private JsonNode readFeed(String accessToken, String cursor, Integer limit) throws Exception {
        return readFeed(accessToken, cursor, limit, List.of());
    }

    /** Sends the filter as repeated parameters; the comma-separated form is covered separately. */
    private JsonNode readFeed(String accessToken, String cursor, Integer limit, List<String> eventTypes)
            throws Exception {
        var request = get(FEED_PATH).header("Authorization", "Bearer " + accessToken);
        if (cursor != null) {
            request = request.param("cursor", cursor);
        }
        if (limit != null) {
            request = request.param("limit", String.valueOf(limit));
        }
        if (!eventTypes.isEmpty()) {
            request = request.param("eventTypes", eventTypes.toArray(String[]::new));
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<JsonNode> itemsOf(JsonNode feedResponse) {
        List<JsonNode> items = new ArrayList<>();
        feedResponse.get("items").forEach(items::add);
        return items;
    }

    private static List<String> eventTypesOf(List<JsonNode> events) {
        return events.stream().map(event -> event.get("eventType").asText()).toList();
    }

    private static List<String> eventIdsOf(List<JsonNode> events) {
        return events.stream().map(event -> event.get("eventId").asText()).toList();
    }

    private PartnerFixture onboardPartner(String label) throws Exception {
        String code = label + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult lspResult = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "LSP " + code,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String lspId = objectMapper.readTree(lspResult.getResponse().getContentAsString()).get("id").asText();

        String productCode = "PRODUCT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult productResult = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", productCode,
                                "name", "Product " + productCode,
                                "minPrincipal", new BigDecimal("5000.00"),
                                "maxPrincipal", new BigDecimal("250000.00"),
                                "interestRate", new BigDecimal("18.50"),
                                "processingFeeRate", new BigDecimal("2.25"),
                                "minTenureMonths", 6,
                                "maxTenureMonths", 24,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String productId = objectMapper.readTree(productResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());

        MvcResult apiClientResult = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", code + " Integration",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode apiClient = objectMapper.readTree(apiClientResult.getResponse().getContentAsString());

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("accessToken").asText();

        // A borrower identity of its own: two partners onboarding the same natural person is a real
        // scenario but an irrelevant one here, and it drags unrelated shared-borrower rules into a
        // test about feed isolation.
        int identity = IDENTITY_SEQUENCE.incrementAndGet();
        return new PartnerFixture(
                lspId,
                productId,
                accessToken,
                String.format("ABCDE%04dF", identity % 10_000),
                String.format("9%09d", identity)
        );
    }

    private String createApplication(PartnerFixture partner, String externalLoanId) throws Exception {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", partner.lspId());
        payload.put("productId", partner.productId());
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Anika Sharma");
        payload.put("emailAddress", "anika@example.com");
        payload.put("mobileNumber", partner.borrowerMobile());
        payload.put("dob", "1992-03-10");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", partner.borrowerPan());
        payload.put("loanAmount", new BigDecimal("45000.00"));
        payload.put("interestRate", new BigDecimal("18.50"));
        payload.put("loanTenure", 12);
        payload.put("addressLine1", "Palm Residency");
        payload.put("addressLine2", "Andheri East");
        payload.put("addressCity", "Mumbai");
        payload.put("addressState", "Maharashtra");
        payload.put("addressZipcode", "400001");
        payload.put("employmentStatus", "SALARIED");
        payload.put("organizationName", "Apex Corp");
        payload.put("empId", "EMP-001");
        payload.put("employmentCity", "Mumbai");
        payload.put("employmentState", "Maharashtra");
        payload.put("employmentZip", "400001");
        payload.put("monthlyIncome", new BigDecimal("78000.00"));
        payload.put("annualIncome", new BigDecimal("936000.00"));
        payload.put("bankAccountNumber", "123456789012");
        payload.put("bankName", "Demo Bank");
        payload.put("ifscCode", "HDFC0001234");
        payload.put("accountHolderName", "Anika Sharma");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");

        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + partner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private JsonNode applicationDetail(String accessToken, String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loanAccountId(String accessToken, String applicationId) throws Exception {
        return applicationDetail(accessToken, applicationId).get("loanAccount").get("id").asText();
    }

    private void uploadAllRequiredDocuments(String accessToken, String applicationId) throws Exception {
        List<Map<String, Object>> documentMetadata = new ArrayList<>();
        var requestBuilder = multipart("/api/v1/lsp/loan-applications/{applicationId}/documents/batch", applicationId);
        requestBuilder.header("Authorization", "Bearer " + accessToken);

        for (String documentType : List.of(
                "PAN_CARD",
                "AADHAAR_FILE",
                "ADDRESS_PROOF",
                "INCOME_PROOF",
                "BANK_STATEMENT",
                "SELFIE_PHOTOGRAPH",
                "KFS",
                "LOAN_AGREEMENT"
        )) {
            documentMetadata.add(Map.of(
                    "documentType", documentType,
                    "note", "Uploaded " + documentType,
                    "sourceReference", "src-" + documentType
            ));
            requestBuilder.file(new MockMultipartFile(
                    "files",
                    documentType.toLowerCase() + ".pdf",
                    "application/pdf",
                    ("%PDF-1.4 content-" + documentType).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        }
        requestBuilder.file(new MockMultipartFile(
                "documents",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(documentMetadata)
        ));
        mockMvc.perform(requestBuilder).andExpect(status().isOk());
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests",
                        applicationId
                ).with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private void resolveDisbursement(String applicationId) throws Exception {
        applyMockDisbursementOutcome(applicationId, "DISBURSED");
    }

    private void parkDisbursementForReconciliation(String applicationId) throws Exception {
        applyMockDisbursementOutcome(applicationId, "PENDING_RECONCILIATION");
    }

    /** Resolves as a TECHNICAL decline — a failed attempt, not a rejected loan. */
    private void failDisbursement(String applicationId) throws Exception {
        applyMockDisbursementOutcome(applicationId, "FAILED");
    }

    private void applyMockDisbursementOutcome(String applicationId, String outcome) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests/mock-outcome",
                        applicationId
                )
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("outcome", outcome))))
                .andExpect(status().isOk());
    }

    /**
     * Runs the scheduled alert-rule evaluation exactly the way {@code AlertRuleSchedulerWorker} does in
     * production: under admin tenant scope. Unlike {@code AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest},
     * this class carries no {@code TenantContextTestExecutionListener}, so nothing else establishes a
     * tenant scope covering the whole test method.
     */
    private void evaluateScheduledRulesAsAdmin() {
        TenantScopedExecution.runAsAdmin(alertRuleEvaluationWorker::evaluateScheduledRules);
    }

    /**
     * Backdates or forward-dates installment 1, the same lever
     * {@code AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest#setFirstInstallmentDueDate} pulls.
     * Wrapped in the admin scope for the same reason as {@link #insertBackdatedLoanEvent}: this
     * {@code JdbcTemplate} sits on the app's real tenant-routing datasource, which requires a resolved
     * scope for every acquisition regardless of statement content.
     */
    private void setFirstInstallmentDueDate(String loanAccountId, LocalDate dueDate) {
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(
                """
                UPDATE loan_repayment_schedule_installment
                SET due_date = ?, updated_at = CURRENT_TIMESTAMP
                WHERE loan_account_id = ? AND installment_number = 1
                """,
                dueDate,
                UUID.fromString(loanAccountId)
        ));
    }

    /**
     * Forces {@code UNDER_REPAYMENT} directly, bypassing the ordinary status-transition command path, the
     * same way {@code AlertRuleEvaluationWorkerDpdBucketTransitionIntegrationTest#seedUnderRepaymentLoan}
     * does — the delinquency evaluation query only considers applications already in that status.
     */
    private void transitionApplicationToUnderRepayment(String applicationId) {
        TenantScopedExecution.runAsAdmin(() -> jdbcTemplate.update(
                "UPDATE loan_application SET status = 'UNDER_REPAYMENT', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                UUID.fromString(applicationId)
        ));
    }

    /**
     * Filters client-side rather than via the {@code eventTypes} query parameter, so this helper works
     * even for an event type the feed does not yet recognise as filterable.
     */
    private List<JsonNode> delinquencyEvents(String accessToken) throws Exception {
        return itemsOf(readFeed(accessToken, null, 500)).stream()
                .filter(event -> "LOAN_DELINQUENCY_BUCKET_CHANGED".equals(event.get("eventType").asText()))
                .toList();
    }

    private void recordPayment(String accessToken, String loanId) throws Exception {
        MvcResult scheduleResult = mockMvc.perform(get("/api/v1/lsp/loans/{loanId}/repayment-schedule", loanId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode schedule = objectMapper.readTree(scheduleResult.getResponse().getContentAsString());
        String installmentId = schedule.get(0).get("id").asText();
        BigDecimal amount = schedule.get(0).get("outstandingAmount").decimalValue();

        mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/payments", loanId)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetInstallmentId", installmentId,
                                "amount", amount,
                                "postedAt", LocalDate.now().minusDays(1).toString(),
                                "reference", "PAY-FEED-001",
                                "channel", "UPI"
                        ))))
                .andExpect(status().isOk());
    }

    private record PartnerFixture(
            String lspId,
            String productId,
            String accessToken,
            String borrowerPan,
            String borrowerMobile
    ) {
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(jwt -> jwt.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
            .JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(jwt -> jwt.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }
}
