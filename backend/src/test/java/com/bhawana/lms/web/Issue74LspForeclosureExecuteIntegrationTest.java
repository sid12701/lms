package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.config.BusinessCalendar;
import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanEventType;
import com.bhawana.lms.domain.LoanForeclosureQuote;
import com.bhawana.lms.domain.LoanForeclosureQuoteStatus;
import com.bhawana.lms.domain.LoanPaymentChannel;
import com.bhawana.lms.domain.LoanPaymentTransaction;
import com.bhawana.lms.domain.LoanRepaymentScheduleInstallment;
import com.bhawana.lms.repo.LoanApplicationRepository;
import com.bhawana.lms.domain.LoanApplicationDocumentChecklistStatus;
import com.bhawana.lms.domain.OpsAlertType;
import com.bhawana.lms.repo.LoanApplicationAuditEventRepository;
import com.bhawana.lms.repo.LoanAccountRepository;
import com.bhawana.lms.repo.LoanApplicationDocumentChecklistRepository;
import com.bhawana.lms.repo.LoanForeclosureQuoteRepository;
import com.bhawana.lms.repo.LoanPaymentTransactionRepository;
import com.bhawana.lms.repo.LoanRepaymentScheduleInstallmentRepository;
import com.bhawana.lms.repo.OpsAlertRepository;
import com.bhawana.lms.service.DisbursementIntentWorkflowService;
import com.bhawana.lms.service.LoanDisbursementCommandService;
import com.bhawana.lms.service.LoanEventLog;
import com.bhawana.lms.support.IntegrationTestDatabaseCleaner;
import com.bhawana.lms.support.TenantContextTestExecutionListener;
import com.bhawana.lms.tenant.TenantScopedExecution;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(
        value = TenantContextTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS
)
class Issue74LspForeclosureExecuteIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IntegrationTestDatabaseCleaner integrationTestDatabaseCleaner;

    @Autowired
    private LoanApplicationDocumentChecklistRepository loanApplicationDocumentChecklistRepository;

    @Autowired
    private LoanApplicationAuditEventRepository loanApplicationAuditEventRepository;

    @Autowired
    private LoanPaymentTransactionRepository loanPaymentTransactionRepository;

    @Autowired
    private OpsAlertRepository opsAlertRepository;

    @Autowired
    private LoanApplicationRepository loanApplicationRepository;

    @Autowired
    private DisbursementIntentWorkflowService disbursementIntentWorkflowService;

    @Autowired
    private LoanDisbursementCommandService loanDisbursementCommandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoanAccountRepository loanAccountRepository;

    @Autowired
    private LoanForeclosureQuoteRepository loanForeclosureQuoteRepository;

    @Autowired
    private LoanRepaymentScheduleInstallmentRepository loanRepaymentScheduleInstallmentRepository;

    @Autowired
    private BusinessCalendar businessCalendar;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private LoanEventLog loanEventLog;

    @BeforeEach
    void setUp() {
        integrationTestDatabaseCleaner.cleanIntegrationTestData();
        Mockito.reset(loanEventLog);
    }

    @Test
    void lspCanExecuteActiveForeclosureQuoteAndLoanCloses() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-EXEC-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-12345",
                                "note", "Borrower settled in full"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.id").value(quoteId));

        mockMvc.perform(get("/api/v1/lsp/loans/{loanId}", fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FORECLOSED"))
                .andExpect(jsonPath("$.loanAccount.status").value("FORECLOSED"));

        assertTrue(loanApplicationAuditEventRepository.findTop25ByLoanApplication_IdOrderByCreatedAtDesc(
                        UUID.fromString(fixture.applicationId()))
                .stream()
                .anyMatch(event -> event.getAction() == LoanApplicationAuditAction.FORECLOSURE_EXECUTED
                        && fixture.clientId().equals(event.getActorUsername())));
    }

    @Test
    void lspUiWriteRoleCanExecuteForeclosureQuote() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-UI-WRITE-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .with(lspUiWriteUser(fixture.lspId(), "FC LSP"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-UI-001"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
    }

    @Test
    void lspUiReadRoleCannotExecuteForeclosureQuote() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-UI-READ-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .with(lspUiReadUser(fixture.lspId(), "FC LSP"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-READ-001"
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void crossTenantLspCannotExecuteAnotherLspsForeclosureQuote() throws Exception {
        DisbursedLoanFixture alpha = seedDisbursedLoan("FC-TENANT-A");
        DisbursedLoanFixture beta = seedDisbursedLoan("FC-TENANT-B");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(alpha.accessToken(), alpha.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        alpha.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + beta.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-CROSS"
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("Unknown loan id")));

        assertEquals(0, opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION)
                .count());
    }

    @Test
    void idempotencyKeySameRequestReturnsCachedResponseAndNoDoubleSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-IDEM-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "settlementDate", effectiveDate.toString(),
                "reference", "BNK-IDEM-001"
        ));

        MvcResult first = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult second = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        assertSameSettlement(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString()
        );
        assertEquals(
                1,
                loanPaymentTransactionRepository.findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(
                                UUID.fromString(fixture.loanAccountId()))
                        .stream()
                        .filter(payment -> "BNK-IDEM-001".equals(payment.getReference()))
                        .count()
        );
    }

    @Test
    void idempotencyKeyMissingReturnsBadRequest() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-IDEM-MISSING");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.toString(),
                                "reference", "BNK-NO-KEY"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quoteRequestWithOneIdempotencyKeyCreatesOneLogicalQuote() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-QUOTE-IDEM-001");
        LocalDate effectiveDate = businessCalendar.today();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = objectMapper.writeValueAsString(Map.of(
                "effectiveDate", effectiveDate.toString()
        ));

        MvcResult first = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
                        fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        // A replay of the same key returns the stored response — the quote command never reruns,
        // so no second quote row is created and nothing is superseded (M14).
        MvcResult second = mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
                        fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode firstQuote = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondQuote = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(firstQuote.get("id").asText(), secondQuote.get("id").asText());
        assertEquals(1, secondQuote.get("version").asInt());
        assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString()
        );

        List<LoanForeclosureQuote> quotes = loanForeclosureQuoteRepository
                .findByLoanAccount_IdOrderByVersionDesc(UUID.fromString(fixture.loanAccountId()));
        assertEquals(1, quotes.size());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quotes.get(0).getStatus());
    }

    @Test
    void quoteRequestSameKeyWithDifferentPayloadConflicts() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-QUOTE-CONF-001");
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
                        fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", businessCalendar.today().toString()
                        ))))
                .andExpect(status().isOk());

        // The fingerprint covers the whole request — a different effectiveDate under the same key
        // is a different request, not a replay.
        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
                        fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", businessCalendar.today().plusDays(1).toString()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        assertEquals(
                1,
                loanForeclosureQuoteRepository
                        .findByLoanAccount_IdOrderByVersionDesc(UUID.fromString(fixture.loanAccountId()))
                        .size()
        );
    }

    @Test
    void quoteRequestMalformedKeyIsRejectedBeforeAnyQuoteExists() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-QUOTE-BADKEY");
        LocalDate effectiveDate = businessCalendar.today();

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quote",
                        fixture.loanAccountId())
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", effectiveDate.toString()
                        ))))
                .andExpect(status().isBadRequest());

        assertEquals(
                0,
                loanForeclosureQuoteRepository
                        .findByLoanAccount_IdOrderByVersionDesc(UUID.fromString(fixture.loanAccountId()))
                        .size()
        );
    }

    @Test
    void quoteRequestWithoutKeyKeepsLegacySupersedingBehavior() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-QUOTE-LEGACY");
        LocalDate effectiveDate = businessCalendar.today();

        String firstId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        String secondId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        // Distinct requests still create fresh quotes — only keyed retries are deduplicated (M14).
        assertNotEquals(firstId, secondId);
        List<LoanForeclosureQuote> quotes = loanForeclosureQuoteRepository
                .findByLoanAccount_IdOrderByVersionDesc(UUID.fromString(fixture.loanAccountId()));
        assertEquals(2, quotes.size());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quotes.get(0).getStatus());
        assertEquals(LoanForeclosureQuoteStatus.SUPERSEDED, quotes.get(1).getStatus());
    }

    @Test
    void settlementDateMismatchReturns422AndFiresLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-DATE-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        quoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.minusDays(1).toString(),
                                "reference", "BNK-DATE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("SETTLEMENT_DATE_MISMATCH"));

        assertTrue(opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && alert.getContextJson().contains("SETTLEMENT_DATE_MISMATCH")));
    }

    @Test
    void inactiveQuoteReturns422AndFiresLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-INACTIVE-001");
        LocalDate firstDate = businessCalendar.today();
        String firstQuoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), firstDate);
        requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), firstDate);

        mockMvc.perform(post(
                        "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.loanAccountId(),
                        firstQuoteId)
                        .header("Authorization", "Bearer " + fixture.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", firstDate.toString(),
                                "reference", "BNK-STALE"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("QUOTE_NOT_ACTIVE"));

        assertTrue(opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && alert.getContextJson().contains("QUOTE_NOT_ACTIVE")));
    }

    @Test
    void adminExecuteSameFailureDoesNotFireLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ADMIN-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        mockMvc.perform(post(
                        "/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute",
                        fixture.applicationId(),
                        quoteId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "settlementDate", effectiveDate.minusDays(1).toString(),
                                "reference", "FC-ADMIN"
                        ))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_DATE_MISMATCH"));

        assertEquals(0, opsAlertRepository.findAll().stream()
                .filter(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION)
                .count());
    }

    // --- C05: a quote is only redeemable while the schedule still owes what it quoted ---

    @Test
    void receiptRecordedAfterQuoteMakesExecutionStaleAndWritesNoSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-STALE-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        payInstallment(fixture.applicationId(), 1, "PAY-STALE-001")
                .andExpect(status().isOk());

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-STALE-001", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("FORECLOSURE_QUOTE_STALE"));

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quoteStatus(quoteId));
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
        assertEquals(LoanApplicationStatus.UNDER_REPAYMENT, applicationStatus(fixture.applicationId()));
        assertTrue(opsAlertRepository.findAll().stream()
                .anyMatch(alert -> alert.getType() == OpsAlertType.LSP_BOUND_VIOLATION
                        && alert.getContextJson().contains("FORECLOSURE_QUOTE_STALE")));
    }

    @Test
    void receiptAfterForeclosureIsRejectedByTheClosedStatePolicy() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-CLOSED-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        // Captured while it is still due: after closure the installment owes nothing, and the
        // request has to reach the closed-state rule rather than fail as a zero-amount payment.
        Map<String, Object> firstInstallment = installmentRow(fixture.applicationId(), 1);

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-CLOSED-001", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        payInstallment(fixture.applicationId(), firstInstallment, "PAY-CLOSED-001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("REPAYMENT_NOT_ALLOWED"));

        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
    }

    @Test
    void receiptThatCommitsFirstMakesAConcurrentExecutionStale() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-RACE-RECEIPT");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        Map<String, Object> firstInstallment = installmentRow(fixture.applicationId(), 1);

        RaceOutcome race = race(
                LoanEventType.LOAN_REPAYMENT_RECORDED,
                () -> payInstallment(fixture.applicationId(), firstInstallment, "PAY-RACE-001"),
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-RACE-001", UUID.randomUUID().toString())
        );

        assertEquals(200, race.winner().getStatus(), race.winner().getContentAsString());
        assertEquals(422, race.loser().getStatus(), race.loser().getContentAsString());
        assertEquals("FORECLOSURE_QUOTE_STALE", violationType(race.loser()));
        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quoteStatus(quoteId));
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
        assertEquals(0, foreclosureExecutedAuditCount(fixture.applicationId()));
    }

    @Test
    void executionThatCommitsFirstRejectsAConcurrentReceipt() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-RACE-EXECUTE");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        Map<String, Object> firstInstallment = installmentRow(fixture.applicationId(), 1);

        RaceOutcome race = race(
                LoanEventType.LOAN_FORECLOSURE_COMPLETED,
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-RACE-002", UUID.randomUUID().toString()),
                () -> payInstallment(fixture.applicationId(), firstInstallment, "PAY-RACE-002")
        );

        assertEquals(200, race.winner().getStatus(), race.winner().getContentAsString());
        assertCleanRejection(race.loser());
        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertTrue(paymentRows("PAY-RACE-002").isEmpty());
        assertEquals(LoanForeclosureQuoteStatus.EXECUTED, quoteStatus(quoteId));
        assertEquals(LoanAccountStatus.FORECLOSED, loanAccountStatus(fixture.loanAccountId()));
        assertEquals(LoanApplicationStatus.FORECLOSED, applicationStatus(fixture.applicationId()));
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
    }

    @Test
    void concurrentExecutionsOfOneQuoteProduceExactlyOneSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ONCE-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        RaceOutcome race = race(
                LoanEventType.LOAN_FORECLOSURE_COMPLETED,
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-ONCE-A", UUID.randomUUID().toString()),
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-ONCE-B", UUID.randomUUID().toString())
        );

        assertEquals(200, race.winner().getStatus(), race.winner().getContentAsString());
        assertEquals(422, race.loser().getStatus(), race.loser().getContentAsString());
        assertEquals("IDEMPOTENCY_CONFLICT", violationType(race.loser()));
        List<LoanPaymentTransaction> settlements = settlementReceipts(fixture.loanAccountId());
        assertEquals(1, settlements.size());
        assertEquals("BNK-ONCE-A", settlements.get(0).getReference());
        assertEquals(quoteId, foreclosureQuoteIdOf(settlements.get(0).getId()));
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
        verifyForeclosureCompletedEvents(1);
    }

    @Test
    void concurrentIdenticalExecutionsUnderDifferentKeysSettleOnceAndBothSeeTheResult() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ONCE-002");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        RaceOutcome race = race(
                LoanEventType.LOAN_FORECLOSURE_COMPLETED,
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-ONCE-C", UUID.randomUUID().toString()),
                () -> executeForeclosure(
                        fixture, quoteId, effectiveDate, "BNK-ONCE-C", UUID.randomUUID().toString())
        );

        assertEquals(200, race.winner().getStatus(), race.winner().getContentAsString());
        assertEquals(200, race.loser().getStatus(), race.loser().getContentAsString());
        assertSameSettlement(race.winner().getContentAsString(), race.loser().getContentAsString());
        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
        verifyForeclosureCompletedEvents(1);
    }

    @Test
    void executingAnAlreadyExecutedQuoteUnderANewKeyIsACleanConflict() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-REEXEC-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-REEXEC-001", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-REEXEC-002", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("IDEMPOTENCY_CONFLICT"));
        // The command-level answer, with no HTTP idempotency layer in front of it.
        executeForeclosureAsAdmin(fixture, quoteId, effectiveDate, "BNK-REEXEC-003")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
    }

    @Test
    void reExecutingAnExecutedQuoteWithTheSameRequestReturnsTheOriginalSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-REPLAY-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        String original = executeForeclosure(
                fixture, quoteId, effectiveDate, "BNK-REPLAY-001", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> quoteRowAfterExecution = quoteRow(quoteId);

        // A new HTTP key misses every idempotency cache, so the replay is the command's own.
        String replayed = executeForeclosure(
                fixture, quoteId, effectiveDate, "BNK-REPLAY-001", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The replay itself writes nothing, as the row comparison below shows.
        assertSameSettlement(original, replayed);
        executeForeclosureAsAdmin(fixture, quoteId, effectiveDate, "BNK-REPLAY-001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quoteId))
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertEquals(quoteRowAfterExecution, quoteRow(quoteId));
        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
        verifyForeclosureCompletedEvents(1);
        assertEquals(LoanApplicationStatus.FORECLOSED, applicationStatus(fixture.applicationId()));
    }

    @Test
    void settlementReceiptIsLinkedToTheQuoteItRedeems() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LINK-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-LINK-001", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        LoanPaymentTransaction settlement = settlementReceipts(fixture.loanAccountId()).get(0);
        assertEquals(quoteId, foreclosureQuoteIdOf(settlement.getId()));
        assertEquals(
                0,
                quoteOf(quoteId).getSettlementAmount().compareTo(settlement.getAmount())
        );
        assertEquals(0, settlement.getUnallocatedAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, settlement.getAllocatedAmount().compareTo(settlement.getAmount()));
        assertNull(settlement.getIdempotencyKey());
        assertEquals(64, settlement.getRequestFingerprint().length());
    }

    @Test
    void migrationSupersedesLegacyActiveQuotesSoTheyCannotExecute() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LEGACY-OK");
        LocalDate effectiveDate = businessCalendar.today();
        // Amounts that still match the schedule exactly: even a legacy quote that looks fresh is
        // superseded, because nothing about how it was issued can be trusted.
        String legacyQuoteId = insertQuote(fixture.loanAccountId(), 98, effectiveDate, BigDecimal.ZERO, "ACTIVE");
        String executedQuoteId = insertQuote(fixture.loanAccountId(), 97, effectiveDate, BigDecimal.ZERO, "EXECUTED");

        runSupersedeLegacyQuotesMigrationSection();

        assertEquals(LoanForeclosureQuoteStatus.SUPERSEDED, quoteStatus(legacyQuoteId));
        assertEquals(LoanForeclosureQuoteStatus.EXECUTED, quoteStatus(executedQuoteId));
        executeForeclosure(fixture, legacyQuoteId, effectiveDate, "BNK-LEGACY-OK", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("QUOTE_NOT_ACTIVE"));

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
    }

    @Test
    void activeQuoteAskingForMoreThanIsOwedIsRejectedAsStale() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LEGACY-EXCESS");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = insertQuote(fixture.loanAccountId(), 99, effectiveDate, new BigDecimal("1000.00"), "ACTIVE");

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-LEGACY-EXCESS", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("FORECLOSURE_QUOTE_STALE"));

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
    }

    @Test
    void quoteForAnyDayButTheCurrentBusinessDateIsRefusedAtRequest() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-BACKDATED-001");
        LocalDate today = businessCalendar.today();

        for (LocalDate effectiveDate : List.of(today.minusDays(3), today.plusDays(1))) {
            mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/foreclosure-quote", fixture.loanAccountId())
                            .header("Authorization", "Bearer " + fixture.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "effectiveDate", effectiveDate.toString()
                            ))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error").value("FORECLOSURE_QUOTE_DATE_INVALID"));
        }

        assertTrue(loanForeclosureQuoteRepository
                .findByLoanAccount_IdOrderByVersionDesc(UUID.fromString(fixture.loanAccountId()))
                .isEmpty());
    }

    @Test
    void quoteFromAnEarlierBusinessDayCannotBeExecuted() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-BACKDATED-002");
        LocalDate yesterday = businessCalendar.today().minusDays(1);
        // Balances still match, so only the date can refuse it.
        String quoteId = insertQuote(fixture.loanAccountId(), 1, yesterday, BigDecimal.ZERO, "ACTIVE");

        executeForeclosure(fixture, quoteId, yesterday, "BNK-BACKDATED-A", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("FORECLOSURE_QUOTE_DATE_INVALID"));
        executeForeclosure(fixture, quoteId, businessCalendar.today(), "BNK-BACKDATED-B", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("SETTLEMENT_DATE_MISMATCH"));

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quoteStatus(quoteId));
    }

    @Test
    void executionFailureLeavesNoPartialSettlementAndALaterExecutionIsClean() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ROLLBACK-001");
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        Map<String, Object> scheduleBefore = scheduleSnapshot(fixture.applicationId());

        Mockito.doThrow(new RuntimeException("loan event append failed"))
                .when(loanEventLog)
                .append(
                        Mockito.any(),
                        Mockito.eq(LoanEventType.LOAN_FORECLOSURE_COMPLETED),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()
                );

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-ROLLBACK-001", UUID.randomUUID().toString())
                .andExpect(status().is5xxServerError());

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quoteStatus(quoteId));
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
        assertEquals(0, foreclosureExecutedAuditCount(fixture.applicationId()));
        assertEquals(scheduleBefore, scheduleSnapshot(fixture.applicationId()));

        Mockito.reset(loanEventLog);
        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-ROLLBACK-002", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
    }

    // --- H09: foreclosure allocates only its own receipt, never replays payment history ---

    @Test
    void foreclosureLeavesAnEarlierReceiptAllocatedToItsOwnInstallment() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-H09-001");
        payInstallment(fixture.applicationId(), 3, "PAY-H09-003").andExpect(status().isOk());

        Map<String, Object> receiptBefore = paymentRow("PAY-H09-003");
        Map<String, Object> thirdInstallmentBefore = installmentRow(fixture.applicationId(), 3);

        String quoteId = foreclose(fixture, "BNK-H09-001");

        assertEquals(receiptBefore, paymentRow("PAY-H09-003"));
        assertEquals(thirdInstallmentBefore, installmentRow(fixture.applicationId(), 3));
        assertSettledExactlyByTheQuote(fixture, quoteId);
        assertTrue(loanPaymentTransactionRepository.findReceiptsExceedingTheirInstallmentPayment().isEmpty());
    }

    @Test
    void foreclosureLeavesALegacyReceiptRowUntouched() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-H09-LEGACY");
        // Pre-V130 shape: no idempotency key, no fingerprint, no quote link, targeted at one
        // installment which it paid in full.
        Map<String, Object> secondInstallment = installmentRow(fixture.applicationId(), 2);
        BigDecimal due = ((BigDecimal) secondInstallment.get("principal_due"))
                .add((BigDecimal) secondInstallment.get("interest_due"));
        insertLegacyReceipt(fixture.loanAccountId(), secondInstallment.get("id"), "PAY-H09-LEGACY", due, due);
        markInstallmentPaid(secondInstallment.get("id"),
                (BigDecimal) secondInstallment.get("principal_due"),
                (BigDecimal) secondInstallment.get("interest_due"));

        Map<String, Object> receiptBefore = paymentRow("PAY-H09-LEGACY");
        Map<String, Object> installmentBefore = installmentRow(fixture.applicationId(), 2);

        String quoteId = foreclose(fixture, "BNK-H09-LEGACY");

        assertEquals(receiptBefore, paymentRow("PAY-H09-LEGACY"));
        assertEquals(installmentBefore, installmentRow(fixture.applicationId(), 2));
        assertSettledExactlyByTheQuote(fixture, quoteId);
    }

    @Test
    void foreclosureLeavesAPartiallyAllocatedReceiptAsItWas() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-H09-PARTIAL");
        Map<String, Object> secondInstallment = installmentRow(fixture.applicationId(), 2);
        BigDecimal interestDue = (BigDecimal) secondInstallment.get("interest_due");
        BigDecimal partialPrincipal = new BigDecimal("100.00");
        BigDecimal allocated = interestDue.add(partialPrincipal);
        // 40.00 of this receipt never found an installment; it stays unallocated.
        insertLegacyReceipt(
                fixture.loanAccountId(),
                secondInstallment.get("id"),
                "PAY-H09-PARTIAL",
                allocated.add(new BigDecimal("40.00")),
                allocated
        );
        markInstallmentPaid(secondInstallment.get("id"), partialPrincipal, interestDue);
        Map<String, Object> receiptBefore = paymentRow("PAY-H09-PARTIAL");
        BigDecimal owedBefore = totalDue(fixture).subtract(allocated);

        String quoteId = foreclose(fixture, "BNK-H09-PARTIAL");

        assertEquals(receiptBefore, paymentRow("PAY-H09-PARTIAL"));
        Map<String, Object> secondInstallmentAfter = installmentRow(fixture.applicationId(), 2);
        assertEquals("PAID", secondInstallmentAfter.get("status"));
        assertEquals(0, ((BigDecimal) secondInstallmentAfter.get("paid_principal"))
                .compareTo((BigDecimal) secondInstallment.get("principal_due")));
        // The quote covered only what the schedule still owed; the 40.00 was not absorbed.
        assertEquals(0, quoteOf(quoteId).getSettlementAmount().compareTo(owedBefore));
        assertSettledExactlyByTheQuote(fixture, quoteId);
        assertTrue(loanPaymentTransactionRepository.findReceiptsExceedingTheirInstallmentPayment().isEmpty());
    }

    @Test
    void reconciliationReportsInconsistentAllocationsAndForeclosureDoesNotRepairThem() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-H09-RECON");
        payInstallment(fixture.applicationId(), 2, "PAY-H09-RECON-1").andExpect(status().isOk());
        Map<String, Object> secondInstallment = installmentRow(fixture.applicationId(), 2);
        BigDecimal due = (BigDecimal) secondInstallment.get("installment_amount");
        // Damaged history: a second receipt claims the same installment the first one paid.
        insertLegacyReceipt(fixture.loanAccountId(), secondInstallment.get("id"), "PAY-H09-RECON-2", due, due);
        Map<String, Object> firstBefore = paymentRow("PAY-H09-RECON-1");
        Map<String, Object> secondBefore = paymentRow("PAY-H09-RECON-2");

        assertEquals(
                List.of("PAY-H09-RECON-1", "PAY-H09-RECON-2"),
                inconsistentReceiptReferences()
        );

        foreclose(fixture, "BNK-H09-RECON");

        assertEquals(firstBefore, paymentRow("PAY-H09-RECON-1"));
        assertEquals(secondBefore, paymentRow("PAY-H09-RECON-2"));
        assertEquals(secondInstallment, installmentRow(fixture.applicationId(), 2));
        assertEquals(
                List.of("PAY-H09-RECON-1", "PAY-H09-RECON-2"),
                inconsistentReceiptReferences()
        );
    }

    // --- helpers ---

    private String foreclose(DisbursedLoanFixture fixture, String reference) throws Exception {
        LocalDate effectiveDate = businessCalendar.today();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        executeForeclosure(fixture, quoteId, effectiveDate, reference, UUID.randomUUID().toString())
                .andExpect(status().isOk());
        return quoteId;
    }

    /**
     * The settlement is exactly the quote, fully allocated; every receipt conserves
     * amount = allocated + unallocated; principal and interest are each settled in full.
     */
    private void assertSettledExactlyByTheQuote(DisbursedLoanFixture fixture, String quoteId) {
        List<LoanPaymentTransaction> settlements = settlementReceipts(fixture.loanAccountId());
        assertEquals(1, settlements.size());
        LoanPaymentTransaction settlement = settlements.get(0);
        assertEquals(0, quoteOf(quoteId).getSettlementAmount().compareTo(settlement.getAmount()));
        assertEquals(0, settlement.getUnallocatedAmount().compareTo(BigDecimal.ZERO));

        loanPaymentTransactionRepository
                .findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID.fromString(fixture.loanAccountId()))
                .forEach(payment -> assertEquals(
                        0,
                        payment.getAmount().compareTo(
                                payment.getAllocatedAmount().add(payment.getUnallocatedAmount())
                        )
                ));

        List<LoanRepaymentScheduleInstallment> installments = loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(UUID.fromString(fixture.loanAccountId()));
        assertEquals(
                sum(installments, LoanRepaymentScheduleInstallment::getPrincipalDue),
                sum(installments, LoanRepaymentScheduleInstallment::getPaidPrincipal)
        );
        assertEquals(
                sum(installments, LoanRepaymentScheduleInstallment::getInterestDue),
                sum(installments, LoanRepaymentScheduleInstallment::getPaidInterest)
        );
    }

    private BigDecimal totalDue(DisbursedLoanFixture fixture) {
        return loanRepaymentScheduleInstallmentRepository
                .findByLoanAccount_IdOrderByInstallmentNumberAsc(UUID.fromString(fixture.loanAccountId()))
                .stream()
                .map(installment -> installment.getPrincipalDue().add(installment.getInterestDue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<String> inconsistentReceiptReferences() {
        return loanPaymentTransactionRepository.findReceiptsExceedingTheirInstallmentPayment().stream()
                .map(LoanPaymentTransaction::getReference)
                .sorted()
                .toList();
    }

    private void insertLegacyReceipt(
            String loanAccountId,
            Object installmentId,
            String reference,
            BigDecimal amount,
            BigDecimal allocated
    ) {
        jdbcTemplate.update(
                """
                        insert into loan_payment_transaction (
                            id, loan_account_id, repayment_installment_id, actor_username, amount,
                            payment_date, reference, channel, status, allocated_amount, unallocated_amount
                        ) values (?, ?, ?, 'legacy.ops', ?, ?, ?, 'UPI', 'RECEIVED', ?, ?)
                        """,
                UUID.randomUUID(),
                UUID.fromString(loanAccountId),
                installmentId,
                amount,
                businessCalendar.today().minusDays(10),
                reference,
                allocated,
                amount.subtract(allocated)
        );
    }

    private void markInstallmentPaid(Object installmentId, BigDecimal paidPrincipal, BigDecimal paidInterest) {
        jdbcTemplate.update(
                """
                        update loan_repayment_schedule_installment
                        set paid_principal = ?,
                            paid_interest = ?,
                            paid_amount = ?,
                            outstanding_amount = installment_amount - ?,
                            status = case when installment_amount - ? = 0 then 'PAID' else 'PARTIALLY_PAID' end
                        where id = ?
                        """,
                paidPrincipal,
                paidInterest,
                paidPrincipal.add(paidInterest),
                paidPrincipal.add(paidInterest),
                paidPrincipal.add(paidInterest),
                installmentId
        );
    }

    /**
     * Runs the winner to the end of its transaction — event appended and every write flushed, so
     * it holds all of its row locks uncommitted — then starts the loser, proves the loser is
     * waiting on a PostgreSQL lock, and only then lets the winner commit. The order is forced,
     * not left to the scheduler.
     */
    private RaceOutcome race(LoanEventType winnerHoldsAt, ThrowingCall winner, ThrowingCall loser) throws Exception {
        CountDownLatch winnerHolding = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicBoolean firstAppend = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
                    Object appended = invocation.callRealMethod();
                    if (firstAppend.compareAndSet(true, false)) {
                        entityManager.flush();
                        winnerHolding.countDown();
                        assertTrue(releaseWinner.await(60, TimeUnit.SECONDS));
                    }
                    return appended;
                })
                .when(loanEventLog)
                .append(
                        Mockito.any(),
                        Mockito.eq(winnerHoldsAt),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any(),
                        Mockito.any()
                );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MockHttpServletResponse> winnerResponse = executor.submit(responseOf(winner));
            assertTrue(winnerHolding.await(30, TimeUnit.SECONDS), "winner must reach its hold point");
            Future<MockHttpServletResponse> loserResponse = executor.submit(responseOf(loser));
            awaitLockContention(loserResponse);
            releaseWinner.countDown();
            return new RaceOutcome(
                    winnerResponse.get(60, TimeUnit.SECONDS),
                    loserResponse.get(60, TimeUnit.SECONDS)
            );
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitLockContention(Future<?> loser) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline && !loser.isDone()) {
            Integer waiters = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and pid <> pg_backend_pid()",
                    Integer.class
            );
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(50);
        }
        assertFalse(loser.isDone(), "the loser must still be pending while the winner holds its locks");
        throw new AssertionError("the loser never reached a PostgreSQL lock wait");
    }

    private Callable<MockHttpServletResponse> responseOf(ThrowingCall call) {
        return () -> TenantScopedExecution.callAsAdmin(() -> {
            try {
                return call.perform().andReturn().getResponse();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private String violationType(MockHttpServletResponse response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("LSP_BOUND_VIOLATION", body.get("error").asText());
        return body.get("violations").get(0).get("message").asText();
    }

    private static void assertCleanRejection(MockHttpServletResponse response) {
        assertTrue(response.getStatus() >= 400 && response.getStatus() < 500,
                "expected a clean 4xx rejection, got " + response.getStatus());
    }

    private void verifyForeclosureCompletedEvents(int times) {
        Mockito.verify(loanEventLog, Mockito.times(times)).append(
                Mockito.any(),
                Mockito.eq(LoanEventType.LOAN_FORECLOSURE_COMPLETED),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    private record RaceOutcome(MockHttpServletResponse winner, MockHttpServletResponse loser) {
    }

    private ResultActions executeForeclosureAsAdmin(
            DisbursedLoanFixture fixture,
            String quoteId,
            LocalDate settlementDate,
            String reference
    ) throws Exception {
        return mockMvc.perform(post(
                "/api/v1/internal/ops/loan-applications/{applicationId}/foreclosure-quotes/{quoteId}/execute",
                fixture.applicationId(),
                quoteId)
                .with(systemAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "settlementDate", settlementDate.toString(),
                        "reference", reference
                ))));
    }

    private ResultActions executeForeclosure(
            DisbursedLoanFixture fixture,
            String quoteId,
            LocalDate settlementDate,
            String reference,
            String idempotencyKey
    ) throws Exception {
        return mockMvc.perform(post(
                "/api/v1/lsp/loans/{loanId}/foreclosure-quotes/{quoteId}/execute",
                fixture.loanAccountId(),
                quoteId)
                .header("Authorization", "Bearer " + fixture.accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "settlementDate", settlementDate.toString(),
                        "reference", reference
                ))));
    }

    private ResultActions payInstallment(String applicationId, int installmentNumber, String reference)
            throws Exception {
        return payInstallment(applicationId, installmentRow(applicationId, installmentNumber), reference);
    }

    private ResultActions payInstallment(String applicationId, Map<String, Object> installment, String reference)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetInstallmentId", installment.get("id").toString());
        body.put("amount", installment.get("outstanding_amount"));
        body.put("postedAt", businessCalendar.today().minusDays(1).toString());
        body.put("channel", "UPI");
        body.put("reference", reference);
        return mockMvc.perform(post(
                "/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                .with(systemAdmin())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }


    private List<LoanPaymentTransaction> settlementReceipts(String loanAccountId) {
        return loanPaymentTransactionRepository
                .findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID.fromString(loanAccountId))
                .stream()
                .filter(payment -> payment.getChannel() == LoanPaymentChannel.FORECLOSURE_SETTLEMENT)
                .toList();
    }

    private String foreclosureQuoteIdOf(UUID paymentId) {
        return jdbcTemplate.queryForObject(
                "select foreclosure_quote_id::text from loan_payment_transaction where id = ?",
                String.class,
                paymentId
        );
    }

    private List<Map<String, Object>> paymentRows(String reference) {
        return jdbcTemplate.queryForList("select * from loan_payment_transaction where reference = ?", reference);
    }

    private Map<String, Object> quoteRow(String quoteId) {
        return jdbcTemplate.queryForMap("select * from loan_foreclosure_quote where id = ?", UUID.fromString(quoteId));
    }

    /**
     * Compares two execute responses as the same settlement rather than as the same bytes: a live
     * response renders the in-memory executedAt at nanosecond precision, while a replay re-reads the
     * row PostgreSQL stored at microseconds; updatedAt is left out because the live response is
     * rendered before its own commit-time flush stamps the row once more.
     */
    private void assertSameSettlement(String expectedResponse, String actualResponse) throws Exception {
        JsonNode expected = objectMapper.readTree(expectedResponse);
        JsonNode actual = objectMapper.readTree(actualResponse);
        for (String field : List.of(
                "id", "loanAccountId", "version", "requestedByUsername", "executedByUsername",
                "effectiveDate", "status")) {
            assertEquals(expected.get(field).asText(), actual.get(field).asText(), field);
        }
        assertEquals("EXECUTED", actual.get("status").asText());
        for (String field : List.of("outstandingPrincipal", "outstandingInterest", "settlementAmount")) {
            assertEquals(0, expected.get(field).decimalValue().compareTo(actual.get(field).decimalValue()), field);
        }
        for (String field : List.of("executedAt", "createdAt")) {
            Duration drift = Duration.between(
                    Instant.parse(expected.get(field).asText()), Instant.parse(actual.get(field).asText()));
            // PostgreSQL rounds to the microsecond, so the two renderings agree to within one.
            assertTrue(drift.abs().toNanos() < 1_000, field + " drifted by " + drift);
        }
    }

    private Map<String, Object> paymentRow(String reference) {
        return jdbcTemplate.queryForMap("select * from loan_payment_transaction where reference = ?", reference);
    }

    private Map<String, Object> installmentRow(String applicationId, int installmentNumber) {
        return jdbcTemplate.queryForMap(
                """
                        select i.*
                        from loan_repayment_schedule_installment i
                        join loan_account a on a.id = i.loan_account_id
                        where a.loan_application_id = ?
                          and i.installment_number = ?
                        """,
                UUID.fromString(applicationId),
                installmentNumber
        );
    }

    private Map<String, Object> scheduleSnapshot(String applicationId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                """
                        select i.*
                        from loan_repayment_schedule_installment i
                        join loan_account a on a.id = i.loan_account_id
                        where a.loan_application_id = ?
                        order by i.installment_number
                        """,
                UUID.fromString(applicationId)
        ).forEach(row -> snapshot.put(row.get("installment_number").toString(), row));
        return snapshot;
    }

    /**
     * A quote row written straight to the table: pre-V130 legacy quotes, or quotes whose date or
     * amounts no request could produce today. {@code excess} inflates the principal above what
     * the schedule owes.
     */
    private String insertQuote(
            String loanAccountId,
            int version,
            LocalDate effectiveDate,
            BigDecimal excess,
            String status
    ) {
        Map<String, Object> owed = jdbcTemplate.queryForMap(
                """
                        select coalesce(sum(greatest(principal_due - paid_principal, 0)), 0) as principal,
                               coalesce(sum(greatest(interest_due - paid_interest, 0)), 0) as interest
                        from loan_repayment_schedule_installment
                        where loan_account_id = ?
                        """,
                UUID.fromString(loanAccountId)
        );
        BigDecimal principal = ((BigDecimal) owed.get("principal")).add(excess);
        BigDecimal interest = (BigDecimal) owed.get("interest");
        UUID quoteId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        insert into loan_foreclosure_quote (
                            id, loan_account_id, version, requested_by_username, effective_date,
                            outstanding_principal, outstanding_interest, settlement_amount, status,
                            created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                quoteId,
                UUID.fromString(loanAccountId),
                version,
                "legacy.ops",
                effectiveDate,
                principal,
                interest,
                principal.add(interest),
                status,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );
        return quoteId.toString();
    }

    /** Executes V130's own legacy-quote statement, not a copy of it. */
    private void runSupersedeLegacyQuotesMigrationSection() throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V130__loan_payment_foreclosure_quote_link.sql")) {
            assertNotNull(in, "V130 migration must be on the test classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String start = "-- SUPERSEDE-LEGACY-QUOTES-START";
        String end = "-- SUPERSEDE-LEGACY-QUOTES-END";
        String section = sql.substring(sql.indexOf(start) + start.length(), sql.indexOf(end));
        String statement = section.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .trim();
        jdbcTemplate.update(statement.substring(0, statement.length() - 1));
    }

    private LoanForeclosureQuoteStatus quoteStatus(String quoteId) {
        return quoteOf(quoteId).getStatus();
    }

    private com.bhawana.lms.domain.LoanForeclosureQuote quoteOf(String quoteId) {
        return loanForeclosureQuoteRepository.findById(UUID.fromString(quoteId)).orElseThrow();
    }

    private LoanAccountStatus loanAccountStatus(String loanAccountId) {
        return loanAccountRepository.findById(UUID.fromString(loanAccountId)).orElseThrow().getStatus();
    }

    private LoanApplicationStatus applicationStatus(String applicationId) {
        return loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus();
    }

    private long foreclosureExecutedAuditCount(String applicationId) {
        return loanApplicationAuditEventRepository
                .findTop25ByLoanApplication_IdOrderByCreatedAtDesc(UUID.fromString(applicationId))
                .stream()
                .filter(event -> event.getAction() == LoanApplicationAuditAction.FORECLOSURE_EXECUTED)
                .count();
    }

    private static BigDecimal sum(
            List<LoanRepaymentScheduleInstallment> installments,
            java.util.function.Function<LoanRepaymentScheduleInstallment, BigDecimal> field
    ) {
        return installments.stream()
                .map(field)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        ResultActions perform() throws Exception;
    }

    private String requestForeclosureQuote(String accessToken, String loanAccountId, LocalDate effectiveDate)
            throws Exception {
        MvcResult quoteResult = mockMvc.perform(post("/api/v1/lsp/loans/{loanId}/foreclosure-quote", loanAccountId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "effectiveDate", effectiveDate.toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return objectMapper.readTree(quoteResult.getResponse().getContentAsString()).get("id").asText();
    }

    private DisbursedLoanFixture seedDisbursedLoan(String externalLoanId) throws Exception {
        String lspId = createLspViaAdmin("FC-LSP");
        String productId = createProductViaAdmin();
        mapProductToLsp(productId, lspId);
        JsonNode apiClient = createApiClient(lspId);
        String clientId = apiClient.get("clientId").asText();
        String accessToken = issueToken(apiClient);

        JsonNode application = createApplicationViaLsp(accessToken, lspId, productId, externalLoanId);
        String applicationId = application.get("id").asText();
        markKycComplete(applicationId);
        transitionToAwaitingApproval(applicationId);
        transitionToApproved(applicationId);
        requestDisbursement(applicationId);
        // HDFC fixtures disburse atomically on intent execution — no mock outcome follows.
        disbursementIntentWorkflowService.executeForApplication(UUID.fromString(applicationId));
        loanDisbursementCommandService.autoResolveAfterInitiate(
                UUID.fromString(applicationId), "ops.admin", null, "foreclosure-test");
        assertEquals(
                LoanApplicationStatus.DISBURSED,
                loanApplicationRepository.findById(UUID.fromString(applicationId)).orElseThrow().getStatus()
        );

        MvcResult detail = mockMvc.perform(get("/api/v1/lsp/loan-applications/{applicationId}", applicationId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        String loanAccountId = objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("loanAccount")
                .get("id")
                .asText();

        return new DisbursedLoanFixture(lspId, applicationId, loanAccountId, accessToken, clientId);
    }

    private void requestDisbursement(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/disbursement-requests", applicationId)
                        .with(systemAdmin()))
                .andExpect(status().isOk());
    }

    private JsonNode createApiClient(String lspId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/api-clients")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Foreclosure LSP client",
                                "lspId", lspId,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String issueToken(JsonNode apiClient) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthApiResponses.ClientCredentialsRequest(
                                apiClient.get("clientId").asText(),
                                apiClient.get("clientSecret").asText()
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode createApplicationViaLsp(
            String accessToken,
            String lspId,
            String productId,
            String externalLoanId
    ) throws Exception {
        String pan = uniquePan();
        MvcResult result = mockMvc.perform(post("/api/v1/lsp/loan-applications")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                defaultExternalApplicationPayload(lspId, productId, externalLoanId, pan))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createLspViaAdmin(String codeSuffix) throws Exception {
        String code = "LSP-" + codeSuffix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/lsps")
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Foreclosure LSP " + codeSuffix,
                                "status", "ACTIVE"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createProductViaAdmin() throws Exception {
        String code = "PROD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        MvcResult result = mockMvc.perform(post("/api/v1/internal/admin/products")
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code,
                                "name", "Foreclosure product " + code,
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
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void mapProductToLsp(String productId, String lspId) throws Exception {
        mockMvc.perform(put("/api/v1/internal/admin/product-lsp-mappings/{productId}", productId)
                        .with(productAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("lspIds", List.of(lspId)))))
                .andExpect(status().isOk());
    }

    private void transitionToAwaitingApproval(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "AWAITING_APPROVAL",
                                "note", "Ready for approval"
                        ))))
                .andExpect(status().isOk());
    }

    private void transitionToApproved(String applicationId) throws Exception {
        mockMvc.perform(post("/api/v1/internal/ops/loan-applications/{applicationId}/status-transitions", applicationId)
                        .with(systemAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetStatus", "APPROVED_PENDING_DISBURSAL",
                                "note", "Approved for foreclosure test"
                        ))))
                .andExpect(status().isOk());
    }

    private void markKycComplete(String applicationId) {
        loanApplicationDocumentChecklistRepository.findByLoanApplication_IdOrderByCreatedAtAsc(UUID.fromString(applicationId))
                .forEach(item -> {
                    if (!item.isRequired()) {
                        return;
                    }
                    String documentKey = item.getDocumentType().name().toLowerCase();
                    item.update(
                            LoanApplicationDocumentChecklistStatus.SUBMITTED,
                            "Uploaded for foreclosure test",
                            "ops.user",
                            documentKey + ".pdf",
                            "storage://" + applicationId + "/" + documentKey + ".pdf",
                            null,
                            "application/pdf",
                            1024L,
                            "checksum-" + documentKey,
                            "storage-key/" + applicationId + "/" + documentKey,
                            true
                    );
                    loanApplicationDocumentChecklistRepository.save(item);
                });
    }

    private static Map<String, Object> defaultExternalApplicationPayload(
            String lspId,
            String productId,
            String externalLoanId,
            String pan
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lspId", lspId);
        payload.put("productId", productId);
        payload.put("lspLoanId", externalLoanId);
        payload.put("fullName", "Foreclosure Borrower");
        payload.put("emailAddress", externalLoanId.toLowerCase() + "@example.com");
        payload.put("mobileNumber", mobileForPan(pan));
        payload.put("dob", "1990-01-01");
        payload.put("gender", "FEMALE");
        payload.put("maritalStatus", "SINGLE");
        payload.put("fatherName", "Ramesh Sharma");
        payload.put("aadharNumber", "123412341234");
        payload.put("panNumber", pan);
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
        payload.put("accountHolderName", "Foreclosure Borrower");
        payload.put("referencePersonName", "Neha Verma");
        payload.put("referencePersonNumber", "9888877777");
        return payload;
    }

    private static String uniquePan() {
        int suffix = Math.abs(UUID.randomUUID().hashCode()) % 10_000;
        return String.format("ABCDE%04dF", suffix);
    }

    private static String mobileForPan(String pan) {
        int hash = Math.abs(pan.hashCode());
        return "9" + String.format("%09d", hash % 1_000_000_000);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor systemAdmin() {
        return jwt().jwt(token -> token.subject("ops.admin").claim("roles", List.of("SYSTEM_ADMIN")))
                .authorities(() -> "ROLE_SYSTEM_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor productAdmin() {
        return jwt().jwt(token -> token.subject("product.admin").claim("roles", List.of("PRODUCT_ADMIN")))
                .authorities(() -> "ROLE_PRODUCT_ADMIN");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiReadUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(token -> token
                        .subject("tenant.viewer")
                        .claim("roles", List.of("LSP_UI_READ"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_READ");
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor lspUiWriteUser(
            String lspId,
            String lspName
    ) {
        return jwt().jwt(token -> token
                        .subject("tenant.writer")
                        .claim("roles", List.of("LSP_UI_WRITE"))
                        .claim("lspId", lspId)
                        .claim("lspName", lspName))
                .authorities(() -> "ROLE_LSP_UI_WRITE");
    }

    private record DisbursedLoanFixture(
            String lspId,
            String applicationId,
            String loanAccountId,
            String accessToken,
            String clientId
    ) {
    }
}
