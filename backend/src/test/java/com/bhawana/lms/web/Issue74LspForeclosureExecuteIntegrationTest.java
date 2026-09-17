package com.bhawana.lms.web;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhawana.lms.domain.LoanAccountStatus;
import com.bhawana.lms.domain.LoanApplicationAuditAction;
import com.bhawana.lms.domain.LoanApplicationStatus;
import com.bhawana.lms.domain.LoanEventType;
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
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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

        assertEquals(
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
        LocalDate effectiveDate = LocalDate.now();
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
    void settlementDateMismatchReturns422AndFiresLspBoundViolationAlert() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-DATE-001");
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate firstDate = LocalDate.now();
        LocalDate secondDate = firstDate.plusDays(1);
        String firstQuoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), firstDate);
        requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), secondDate);

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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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
        LocalDate effectiveDate = LocalDate.now();
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
    void concurrentReceiptAndExecutionResolveToExactlyOneOutcome() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-RACE-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = executor.invokeAll(List.of(
                    statusOf(() -> payInstallment(fixture.applicationId(), 1, "PAY-RACE-001")),
                    statusOf(() -> executeForeclosure(
                            fixture, quoteId, effectiveDate, "BNK-RACE-001", UUID.randomUUID().toString()))
            ));
            for (Future<Integer> future : futures) {
                future.get();
            }
        }

        // Whichever thread won, the other one must have been refused: the two outcomes are
        // mutually exclusive and neither leaves a half-applied settlement behind.
        List<LoanPaymentTransaction> settlements = settlementReceipts(fixture.loanAccountId());
        if (settlements.isEmpty()) {
            // The receipt won: the quote went stale and foreclosure touched nothing.
            assertEquals(LoanForeclosureQuoteStatus.ACTIVE, quoteStatus(quoteId));
            assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
            assertEquals(0, foreclosureExecutedAuditCount(fixture.applicationId()));
        } else {
            // Foreclosure won: it settled exactly once and the loan is closed.
            assertEquals(1, settlements.size());
            assertEquals(LoanForeclosureQuoteStatus.EXECUTED, quoteStatus(quoteId));
            assertEquals(LoanAccountStatus.FORECLOSED, loanAccountStatus(fixture.loanAccountId()));
            assertEquals(LoanApplicationStatus.FORECLOSED, applicationStatus(fixture.applicationId()));
            assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
        }
    }

    @Test
    void concurrentExecutionsOfOneQuoteProduceExactlyOneSettlement() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ONCE-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = executor.invokeAll(List.of(
                    statusOf(() -> executeForeclosure(
                            fixture, quoteId, effectiveDate, "BNK-ONCE-A", UUID.randomUUID().toString())),
                    statusOf(() -> executeForeclosure(
                            fixture, quoteId, effectiveDate, "BNK-ONCE-B", UUID.randomUUID().toString()))
            ));
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
        }

        assertEquals(1, statuses.stream().filter(status -> status == 200).count());
        List<LoanPaymentTransaction> settlements = settlementReceipts(fixture.loanAccountId());
        assertEquals(1, settlements.size());
        assertEquals(quoteId, foreclosureQuoteIdOf(settlements.get(0).getId()));
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
        Mockito.verify(loanEventLog, Mockito.times(1)).append(
                Mockito.any(),
                Mockito.eq(LoanEventType.LOAN_FORECLOSURE_COMPLETED),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
        );
    }

    @Test
    void executingAnAlreadyExecutedQuoteUnderANewKeyIsACleanConflict() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-REEXEC-001");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-REEXEC-001", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-REEXEC-002", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LSP_BOUND_VIOLATION"))
                .andExpect(jsonPath("$.violations[0].message").value("QUOTE_NOT_ACTIVE"));

        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(1, foreclosureExecutedAuditCount(fixture.applicationId()));
    }

    @Test
    void settlementReceiptIsLinkedToTheQuoteItRedeems() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LINK-001");
        LocalDate effectiveDate = LocalDate.now();
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
    }

    @Test
    void legacyQuoteExecutesWhileItsStoredAmountsStillMatchTheSchedule() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LEGACY-OK");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = insertLegacyQuote(fixture.loanAccountId(), effectiveDate, BigDecimal.ZERO);

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-LEGACY-OK", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertEquals(1, settlementReceipts(fixture.loanAccountId()).size());
        assertEquals(LoanApplicationStatus.FORECLOSED, applicationStatus(fixture.applicationId()));
    }

    @Test
    void legacyQuoteAskingForMoreThanIsOwedIsRejectedAsStale() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-LEGACY-EXCESS");
        LocalDate effectiveDate = LocalDate.now();
        String quoteId = insertLegacyQuote(fixture.loanAccountId(), effectiveDate, new BigDecimal("1000.00"));

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-LEGACY-EXCESS", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("FORECLOSURE_QUOTE_STALE"));

        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());
        assertEquals(LoanAccountStatus.DISBURSED, loanAccountStatus(fixture.loanAccountId()));
    }

    @Test
    void backdatedQuoteExecutesOnlyOnItsOwnEffectiveDate() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-BACKDATED-001");
        LocalDate effectiveDate = LocalDate.now().minusDays(3);
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);

        executeForeclosure(fixture, quoteId, LocalDate.now(), "BNK-BACKDATED-A", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations[0].message").value("SETTLEMENT_DATE_MISMATCH"));
        assertTrue(settlementReceipts(fixture.loanAccountId()).isEmpty());

        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-BACKDATED-B", UUID.randomUUID().toString())
                .andExpect(status().isOk());
        assertEquals(effectiveDate, settlementReceipts(fixture.loanAccountId()).get(0).getPaymentDate());
    }

    @Test
    void executionFailureLeavesNoPartialSettlementAndALaterExecutionIsClean() throws Exception {
        DisbursedLoanFixture fixture = seedDisbursedLoan("FC-ROLLBACK-001");
        LocalDate effectiveDate = LocalDate.now();
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

        LocalDate effectiveDate = LocalDate.now();
        String quoteId = requestForeclosureQuote(fixture.accessToken(), fixture.loanAccountId(), effectiveDate);
        executeForeclosure(fixture, quoteId, effectiveDate, "BNK-H09-001", UUID.randomUUID().toString())
                .andExpect(status().isOk());

        assertEquals(receiptBefore, paymentRow("PAY-H09-003"));
        assertEquals(thirdInstallmentBefore, installmentRow(fixture.applicationId(), 3));

        LoanPaymentTransaction settlement = settlementReceipts(fixture.loanAccountId()).get(0);
        assertEquals(0, quoteOf(quoteId).getSettlementAmount().compareTo(settlement.getAmount()));
        assertEquals(0, settlement.getUnallocatedAmount().compareTo(BigDecimal.ZERO));

        // amount = allocated + unallocated, for every receipt on the loan.
        loanPaymentTransactionRepository
                .findByLoanAccount_IdOrderByPaymentDateAscCreatedAtAsc(UUID.fromString(fixture.loanAccountId()))
                .forEach(payment -> assertEquals(
                        0,
                        payment.getAmount().compareTo(
                                payment.getAllocatedAmount().add(payment.getUnallocatedAmount())
                        )
                ));

        // Principal and interest are each settled in full, component by component.
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

    // --- helpers ---

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
        body.put("postedAt", LocalDate.now().minusDays(1).toString());
        body.put("channel", "UPI");
        body.put("reference", reference);
        return mockMvc.perform(post(
                "/api/v1/internal/ops/loan-applications/{applicationId}/payments", applicationId)
                .with(systemAdmin())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Callable<Integer> statusOf(ThrowingCall call) {
        return () -> TenantScopedExecution.callAsAdmin(() -> {
            try {
                return call.perform().andReturn().getResponse().getStatus();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
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
     * A quote row as it exists without any of this fix's metadata — its stored amounts are the
     * only freshness evidence there is. {@code excess} inflates them above what the schedule owes.
     */
    private String insertLegacyQuote(String loanAccountId, LocalDate effectiveDate, BigDecimal excess) {
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
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                quoteId,
                UUID.fromString(loanAccountId),
                99,
                "legacy.ops",
                effectiveDate,
                principal,
                interest,
                principal.add(interest),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );
        return quoteId.toString();
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
